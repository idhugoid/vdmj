/*******************************************************************************
 *
 *	Copyright (c) 2018 Nick Battle.
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
 *
 ******************************************************************************/

package com.fujitsu.vdmj.po.annotations;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;

import com.fujitsu.vdmj.po.definitions.PODefinition;
import com.fujitsu.vdmj.po.expressions.POExpression;
import com.fujitsu.vdmj.po.expressions.POExpressionList;
import com.fujitsu.vdmj.po.modules.POModule;
import com.fujitsu.vdmj.po.statements.POStatement;
import com.fujitsu.vdmj.pog.POContextStack;
import com.fujitsu.vdmj.pog.ProofObligationList;
import com.fujitsu.vdmj.tc.lex.TCIdentifierToken;

public abstract class POAnnotation
{
	public final TCIdentifierToken name;
	
	public final POExpressionList args;
	
	private static final Set<Class<?>> declared = new HashSet<Class<?>>(); 

	public POAnnotation(TCIdentifierToken name, POExpressionList args)
	{
		this.name = name;
		this.args = args;
		
		declared.add(this.getClass());
	}

	public static void init()
	{
		for (Class<?> clazz: declared)
		{
			try
			{
				Method doInit = clazz.getMethod("doInit", (Class<?>[])null);
				doInit.invoke(null, (Object[])null);
			}
			catch (Throwable e)
			{
				throw new RuntimeException(clazz.getSimpleName() + ":" + e);
			}
		}
	}
	
	public static void doInit()
	{
		// Nothing by default
	}

	@Override
	public String toString()
	{
		return "@" + name + (args.isEmpty() ? "" : "(" + args + ")");
	}

	public ProofObligationList before(POContextStack ctxt, PODefinition def)
	{
		return new ProofObligationList();
	}

	public ProofObligationList before(POContextStack ctxt, POStatement stmt)
	{
		return new ProofObligationList();
	}

	public ProofObligationList before(POContextStack ctxt, POExpression exp)
	{
		return new ProofObligationList();
	}

	public ProofObligationList before(POModule module)
	{
		return new ProofObligationList();
	}

	public void after(POContextStack ctxt, PODefinition def, ProofObligationList obligations)
	{
		return;
	}

	public void after(POContextStack ctxt, POStatement stmt, ProofObligationList obligations)
	{
		return;
	}

	public void after(POContextStack ctxt, POExpression exp, ProofObligationList obligations)
	{
		return;
	}

	public void after(POModule module, ProofObligationList obligations)
	{
		return;
	}
}
