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

package rpc;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public class RPCDispatcher
{
	private Map<String, RPCHandler> handlers = new HashMap<String, RPCHandler>();
	private RPCHandler unknownHandler = null;
	
	public void register(RPCHandler handler, String method)
	{
		if (method == null)
		{
			unknownHandler = handler;
		}
		else
		{
			handlers.put(method, handler);
		}
	}
	
	public void register(RPCHandler handler, String... methods)
	{
		if (methods.length == 0)
		{
			unknownHandler = handler;
		}
		else
		{
			for (String method: methods)
			{
				handlers.put(method, handler);
			}
		}
	}
	
	public RPCHandler getHandler(RPCRequest request)
	{
		RPCHandler handler = handlers.get(request.getMethod());
		return handler == null ? unknownHandler : handler;
	}

	public RPCMessageList dispatch(RPCRequest request)
	{
		try
		{
			RPCHandler handler = getHandler(request);
			
			if (handler == null)
			{
				return new RPCMessageList(request, RPCErrors.MethodNotFound, request.getMethod());
			}
			else
			{
				return handler.request(request);
			}
		}
		catch (IOException e)
		{
			return new RPCMessageList(request, RPCErrors.InvalidRequest, e.getMessage());
		}
	}
}
