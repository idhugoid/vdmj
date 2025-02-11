/*******************************************************************************
 *
 *	Copyright (c) 2020 Nick Battle.
 *
 *	Author: Nick Battle
 *
 *	This file is part of VDMJ.
 *
 *	VDMJ is free software: you can redistribute it and/or modify
 *	it under the terms of the GNU General Public License as published by
 *	the Free Software Foundation, either version 3 of the License, or
 *	(at your option) any later version.
 *
 *	VDMJ is distributed in the hope that it will be useful,
 *	but WITHOUT ANY WARRANTY; without even the implied warranty of
 *	MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *	GNU General Public License for more details.
 *
 *	You should have received a copy of the GNU General Public License
 *	along with VDMJ.  If not, see <http://www.gnu.org/licenses/>.
 *	SPDX-License-Identifier: GPL-3.0-or-later
 *
 ******************************************************************************/

package workspace;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Vector;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fujitsu.vdmj.Settings;
import com.fujitsu.vdmj.in.expressions.INExpression;
import com.fujitsu.vdmj.in.statements.INStatement;
import com.fujitsu.vdmj.messages.RTLogger;
import com.fujitsu.vdmj.runtime.Breakpoint;
import com.fujitsu.vdmj.runtime.Catchpoint;
import com.fujitsu.vdmj.runtime.Interpreter;
import com.fujitsu.vdmj.scheduler.SchedulableThread;

import dap.AsyncExecutor;
import dap.DAPInitializeResponse;
import dap.DAPMessageList;
import dap.DAPRequest;
import dap.DAPResponse;
import dap.DAPServer;
import dap.InitExecutor;
import dap.RemoteControlExecutor;
import json.JSONArray;
import json.JSONObject;
import lsp.LSPException;
import lsp.LSPServer;
import lsp.Utils;
import rpc.RPCErrors;
import rpc.RPCRequest;
import vdmj.DAPDebugReader;
import vdmj.commands.Command;
import vdmj.commands.PrintCommand;
import workspace.plugins.ASTPlugin;
import workspace.plugins.CTPlugin;
import workspace.plugins.INPlugin;
import workspace.plugins.TCPlugin;

public class DAPWorkspaceManager
{
	private static DAPWorkspaceManager INSTANCE = null;
	private final PluginRegistry registry;
	
	private JSONObject clientCapabilities;
	private Boolean noDebug;
	private Interpreter interpreter;
	private String launchCommand;
	private String defaultName;
	private DAPDebugReader debugReader;
	private String remoteControl;
	
	protected DAPWorkspaceManager()
	{
		this.registry = PluginRegistry.getInstance();
	}

	public static synchronized DAPWorkspaceManager getInstance()
	{
		if (INSTANCE == null)
		{
			INSTANCE = new DAPWorkspaceManager();
		}
		
		return INSTANCE;
	}
	
	/**
	 * This is only used by unit testing.
	 */
	public static void reset()
	{
		if (INSTANCE != null)
		{
			INSTANCE = null;
		}
	}

	/**
	 * DAP methods...
	 */

	public DAPMessageList dapInitialize(DAPRequest request, JSONObject clientCapabilities)
	{
		RTLogger.enable(false);
		this.clientCapabilities = clientCapabilities;
		DAPMessageList responses = new DAPMessageList();
		responses.add(new DAPInitializeResponse(request));
		responses.add(new DAPResponse("initialized", null));
		return responses;
	}

	public DAPMessageList launch(DAPRequest request,
			boolean noDebug, String defaultName, String command, String remoteControl) throws Exception
	{
		if (!canExecute())
		{
			DAPMessageList responses = new DAPMessageList();
			responses.add(new DAPResponse(request, false, "Specification has errors, cannot launch", null));
			stderr("Specification has errors, cannot launch");
			clearInterpreter();
			return responses;
		}
		
		try
		{
			// These values are used in configurationDone
			this.noDebug = noDebug;
			this.launchCommand = command;
			this.defaultName = defaultName;
			this.remoteControl = remoteControl;
			
			processSettings(request);
			
			return new DAPMessageList(request);
		}
		catch (Exception e)
		{
			Diag.error(e);
			return new DAPMessageList(request, e);
		}
	}

	/**
	 * Pick out request arguments that are VDMJ Settings.
	 */
	private void processSettings(DAPRequest request)
	{
		JSONObject args = request.get("arguments");
		
		for (String key: args.keySet())
		{
			switch (key)
			{
				case "dynamicTypeChecks":
					Settings.dynamictypechecks = args.get(key);
					break;
					
				case "invariantsChecks":
					Settings.invchecks = args.get(key);
					break;
					
				case "preConditionChecks":
					Settings.prechecks = args.get(key);
					break;
					
				case "postConditionChecks":
					Settings.postchecks = args.get(key);
					break;
					
				case "measureChecks":
					Settings.measureChecks = args.get(key);
					break;
				
				case "exceptions":
					Settings.exceptions = args.get(key);
					break;
				
				default:
					// Ignore other options
					break;
			}
		}
	}

	public DAPMessageList configurationDone(DAPRequest request) throws IOException
	{
		try
		{
			if (remoteControl != null)
			{
				RemoteControlExecutor exec = new RemoteControlExecutor("remote", request, remoteControl, defaultName);
				exec.start();
			}
			else
			{
				InitExecutor exec = new InitExecutor("init", request, launchCommand, defaultName);
				exec.start();
			}
			
			return new DAPMessageList(request);
		}
		catch (Exception e)
		{
			Diag.error(e);
			return new DAPMessageList(request, e);
		}
		finally
		{
			launchCommand = null;
			remoteControl = null;
		}
	}

	public boolean hasClientCapability(String dotName)
	{
		Boolean cap = getClientCapability(dotName);
		return cap != null && cap;
	}
	
	public <T> T getClientCapability(String dotName)
	{
		T capability = clientCapabilities.getPath(dotName);
		
		if (capability != null)
		{
			Diag.info("Client capability %s = %s", dotName, capability);
			return capability;
		}
		else
		{
			Diag.info("Missing client capability: %s", dotName);
			return null;
		}
	}

	public JSONObject ctRunOneTrace(DAPRequest request, String name, long testNumber) throws LSPException
	{
		CTPlugin ct = registry.getPlugin("CT");
		
		if (ct.isRunning())
		{
			Diag.error("Previous trace is still running...");
			throw new LSPException(RPCErrors.InvalidRequest, "Trace still running");
		}

		/**
		 * If the specification has been modified since we last ran (or nothing has yet run),
		 * we have to re-create the interpreter, otherwise the old interpreter (with the old tree)
		 * is used to "generate" the trace names, so changes are not picked up. Note that a
		 * new tree will have no breakpoints, so if you had any set via a launch, they will be
		 * ignored.
		 */
		refreshInterpreter();
		
		if (specHasErrors())
		{
			throw new LSPException(RPCErrors.ContentModified, "Specification has errors");
		}
		
		noDebug = false;	// Force debug on for runOneTrace

		return ct.runOneTrace(Utils.stringToName(name), testNumber);
	}

	public Interpreter getInterpreter()
	{
		if (interpreter == null)
		{
			try
			{
				TCPlugin tc = registry.getPlugin("TC");
				INPlugin in = registry.getPlugin("IN");
				interpreter = in.getInterpreter(tc.getTC());
			}
			catch (Exception e)
			{
				Diag.error(e);
				interpreter = null;
			}
		}
		
		return interpreter;
	}

	private boolean canExecute()
	{
		ASTPlugin ast = registry.getPlugin("AST");
		TCPlugin tc = registry.getPlugin("TC");
		
		return ast.getErrs().isEmpty() && tc.getErrs().isEmpty();
	}
	
	private boolean hasChanged()
	{
		INPlugin in = registry.getPlugin("IN");
		return getInterpreter() != null && getInterpreter().getIN() != in.getIN();
	}
	
	private boolean isDirty()
	{
		ASTPlugin ast = registry.getPlugin("AST");
		return ast.isDirty();
	}

	private void stdout(String message)
	{
		DAPServer.getInstance().stdout(message);
	}
	
	private void stderr(String message)
	{
		DAPServer.getInstance().stderr(message);
	}
	
	private void sendMessage(Long type, String message)
	{
		try
		{
			LSPServer.getInstance().writeMessage(RPCRequest.notification("window/showMessage",
					new JSONObject("type", type, "message", message)));
		}
		catch (IOException e)
		{
			Diag.error("Failed sending message: ", message);
		}
	}
	
	public DAPMessageList setBreakpoints(DAPRequest request, File file, JSONArray breakpoints) throws Exception
	{
		JSONArray results = new JSONArray();
		
		Map<Integer, Breakpoint> existing = getInterpreter().getBreakpoints();
		Set<Integer> bps = new HashSet<Integer>(existing.keySet());
		
		for (Integer bpno: bps)
		{
			Breakpoint bp = existing.get(bpno);
			
			if (bp.location.file.equals(file))
			{
				interpreter.clearBreakpoint(bpno);
			}
		}
		
		for (Object object: breakpoints)
		{
			JSONObject breakpoint = (JSONObject) object;
			long line = breakpoint.get("line");
			String logMessage = breakpoint.get("logMessage");
			String condition = breakpoint.get("condition");
			
			if (condition == null || condition.isEmpty())
			{
				condition = breakpoint.get("hitCondition");
			}
			
			if (condition != null && condition.isEmpty()) condition = null;

			if (!noDebug)	// debugging allowed!
			{
				INStatement stmt = interpreter.findStatement(file, (int)line);
				
				if (stmt == null)
				{
					INExpression exp = interpreter.findExpression(file, (int)line);
		
					if (exp == null)
					{
						results.add(new JSONObject("verified", false, "message", "No statement or expression here"));
					}
					else
					{
						interpreter.clearBreakpoint(exp.breakpoint.number);
						
						if (logMessage == null || logMessage.isEmpty())
						{
							interpreter.setBreakpoint(exp, condition);
						}
						else
						{
							if (condition != null)
							{
								Diag.error("Ignoring tracepoint condition " + condition);
							}
							
							interpreter.setTracepoint(exp, expressionList(logMessage));
						}
						
						results.add(new JSONObject("verified", true));
					}
				}
				else
				{
					interpreter.clearBreakpoint(stmt.breakpoint.number);
					
					if (logMessage == null || logMessage.isEmpty())
					{
						interpreter.setBreakpoint(stmt, condition);
					}
					else
					{
						if (condition != null)
						{
							Diag.error("Ignoring tracepoint condition " + condition);
						}
						
						interpreter.setTracepoint(stmt, expressionList(logMessage));
					}

					results.add(new JSONObject("verified", true));
				}
			}
			else
			{
				results.add(new JSONObject("verified", false));
			}
		}
		
		return new DAPMessageList(request, new JSONObject("breakpoints", results));
	}
	
	public DAPMessageList setExceptionBreakpoints(DAPRequest request, JSONArray filterOptions)
	{
		for (Catchpoint cp: getInterpreter().getCatchpoints())
		{
			interpreter.clearBreakpoint(cp.number);
		}
		
		JSONArray results = new JSONArray();
		
		if (filterOptions == null)
		{
			String error = "No filterOptions";
			Diag.error(error);
			results.add(new JSONObject("verified", false, "message", error));
		}
		else
		{
			for (int i=0; i<filterOptions.size(); i++)
			{
				JSONObject filterOption = filterOptions.index(i);
				
				if (filterOption.get("filterId").equals("VDM_Exceptions"))
				{
					try
					{
						String condition = filterOption.get("condition");
						interpreter.setCatchpoint(condition);
						results.add(new JSONObject("verified", true));
					}
					catch (Exception e)
					{
						String error = "Illegal condition: " + e.getMessage(); 
						Diag.error(error);
						results.add(new JSONObject("verified", false, "message", error));
						sendMessage(1L, error);
					}
				}
				else
				{
					String error = "Unknown filterOption Id " + filterOption.get("filterId");
					Diag.error(error);
					results.add(new JSONObject("verified", false, "message", error));
					sendMessage(1L, error);
				}
			}
		}

		return new DAPMessageList(request, new JSONObject("breakpoints", results));
	}

	private String expressionList(String trace)
	{
		// Turn a string like "Weight = {x} kilos" into [ "Weight = ", x, " kilos" ]
		
		Pattern p = Pattern.compile("\\{([^{]*)\\}");
		Matcher m = p.matcher(trace);
		StringBuffer sb = new StringBuffer();
		sb.append("[");
		String sep = "";
		
		while(m.find())
		{
			sb.append(sep);
			sb.append(" \"");
		    m.appendReplacement(sb, "\", " + m.group(1));
		    sep = ",";
		}
		
		sb.append(sep);
		sb.append(" \"");
		m.appendTail(sb);
		sb.append("\" ]");
		
		return sb.toString();
	}
	
	public DAPMessageList evaluate(DAPRequest request, String expression, String context)
	{
		CTPlugin ct = registry.getPlugin("CT");
		
		if (ct.isRunning())
		{
			DAPMessageList responses = new DAPMessageList(request,
					new JSONObject("result", "Cannot start interpreter: trace still running?", "variablesReference", 0));
			DAPServer.getInstance().setRunning(false);
			clearInterpreter();
			return responses;
		}
		
		Command command = Command.parse(expression);
		
		if (command.notWhenRunning() && AsyncExecutor.currentlyRunning() != null)
		{
			DAPMessageList responses = new DAPMessageList(request,
					new JSONObject("result", "Still running " + AsyncExecutor.currentlyRunning(), "variablesReference", 0));
			return responses;
		}

		if (command instanceof PrintCommand)	// ie. evaluate something
		{
			if (!canExecute())
			{
				DAPMessageList responses = new DAPMessageList(request,
						new JSONObject("result", "Cannot start interpreter: errors exist?", "variablesReference", 0));
				clearInterpreter();
				return responses;
			}
			else if (hasChanged())
			{
				DAPMessageList responses = new DAPMessageList(request,
						new JSONObject("result", "Specification has changed: try restart", "variablesReference", 0));
				return responses;
			}
			else if (isDirty())
			{
				stderr("WARNING: specification has unsaved changes");
			}
		}
		
		return command.run(request);
	}

	public DAPMessageList threads(DAPRequest request)
	{
		List<SchedulableThread> threads = new Vector<SchedulableThread>(SchedulableThread.getAllThreads());
		Collections.sort(threads);
		JSONArray list = new JSONArray();
		
		for (SchedulableThread thread: threads)
		{
			if (!thread.getName().startsWith("BusThread-"))		// Don't include busses
			{
				list.add(new JSONObject(
					"id",	thread.getId(),
					"name", thread.getName()));
			}
		}
		
		return new DAPMessageList(request, new JSONObject("threads", list));
	}

	/**
	 * Termination and cleanup methods.
	 */
	public DAPMessageList disconnect(DAPRequest request, Boolean terminateDebuggee)
	{
		RTLogger.dump(true);
		stdout("\nSession disconnected.\n");
		SchedulableThread.terminateAll();
		clearInterpreter();
		DAPMessageList result = new DAPMessageList(request);
		return result;
	}

	public DAPMessageList terminate(DAPRequest request, Boolean restart)
	{
		DAPMessageList result = new DAPMessageList(request);
		RTLogger.dump(true);

		if (restart && canExecute())
		{
			stdout("\nSession restarting...\n");
			LSPWorkspaceManager lsp = LSPWorkspaceManager.getInstance();
			lsp.restart();
		}
		else
		{
			if (restart)
			{
				stdout("Cannot restart: specification has errors");
			}
			
			stdout("\nSession terminated.\n");
			result.add(new DAPResponse("terminated", null));
			result.add(new DAPResponse("exit", new JSONObject("exitCode", 0L)));
		}
		
		clearInterpreter();
		return result;
	}
	
	public void clearInterpreter()
	{
		if (interpreter != null)
		{
			// Clear the BPs since they are embedded in the tree and the next
			// launch may have noDebug set.
			
			Set<Integer> bps = new HashSet<Integer>(interpreter.getBreakpoints().keySet());
			
			for (Integer bpno: bps)
			{
				interpreter.clearBreakpoint(bpno);
			}
			
			interpreter = null;
		}
	}
	
	public boolean refreshInterpreter()
	{
		if (hasChanged())
		{
			Diag.info("Specification has changed, resetting interpreter");
			interpreter = null;
			return true;
		}
		
		return false;
	}
	
	private boolean specHasErrors()
	{
		ASTPlugin ast = registry.getPlugin("AST");
		TCPlugin tc = registry.getPlugin("TC");
		
		return !ast.getErrs().isEmpty() || !tc.getErrs().isEmpty();
	}

	public void setDebugReader(DAPDebugReader debugReader)
	{
		this.debugReader = debugReader;
	}
	
	public DAPDebugReader getDebugReader()
	{
		return debugReader;
	}
	
	public boolean getNoDebug()
	{
		return noDebug;
	}
	
	public void stopDebugReader()
	{
		/**
		 * The debugReader field can be cleared at any time, when the debugger ends.
		 * So we take the initial value here.
		 */
		DAPDebugReader reader = debugReader;
		
		if (reader != null)
		{
			int retries = 5;
			
			while (retries-- > 0 && !reader.isListening())
			{
				pause(200);		// Wait for reader to stop & listen
			}
			
			if (retries > 0)
			{
				reader.interrupt();	// Cause exchange to trip & kill threads
				retries = 5;
				
				while (retries-- > 0 && getDebugReader() != null)
				{
					pause(200);
				}
				
				if (retries == 0)
				{
					Diag.error("DAPDebugReader interrupt did not work?");
				}
			}
			else
			{
				Diag.error("DAPDebugReader is not listening?");
			}
		}
	}
	
	private void pause(long ms)
	{
		try
		{
			Thread.sleep(ms);
		}
		catch (InterruptedException e)
		{
			// ignore
		}
	}
}
