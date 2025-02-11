/*******************************************************************************
 *
 *	Copyright (c) 2016 Fujitsu Services Ltd.
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

package com.fujitsu.vdmj.pog;

import com.fujitsu.vdmj.lex.LexLocation;
import com.fujitsu.vdmj.po.patterns.POIgnorePattern;
import com.fujitsu.vdmj.tc.expressions.TCExpression;
import com.fujitsu.vdmj.tc.types.TCNamedType;
import com.fujitsu.vdmj.tc.types.TCType;

abstract public class ProofObligation implements Comparable<ProofObligation>
{
	public final LexLocation location;
	public final POType kind;
	public final String name;

	public int number;
	public String value;
	public POStatus status;
	public POTrivialProof proof;
	public boolean isCheckable;

	private int var = 1;
	private TCExpression checkedExpression = null;

	public ProofObligation(LexLocation location, POType kind, POContextStack ctxt)
	{
		this.location = location;
		this.kind = kind;
		this.name = ctxt.getName();
		this.status = POStatus.UNPROVED;
		this.proof = null;
		this.number = 0;
		this.isCheckable = ctxt.isCheckable();	// Set false for operation POs
		
		if (!isCheckable)
		{
			this.status = POStatus.UNCHECKED;	// Implies unproved
		}
		
		POIgnorePattern.init();		// Reset the "any" count for getMatchingPatterns
	}

	public String getValue()
	{
		return value;
	}

	@Override
	public String toString()
	{
		return  name + ": " + kind + " obligation " + location + "\n" + value;
	}

	protected String getVar(String root)
	{
		return root + var++;
	}

	public void trivialCheck()
	{
		for (POTrivialProof p: POTrivialProof.values())
		{
			if (p.proves(value))
			{
				status = POStatus.TRIVIAL;
				proof = p;
				break;
			}
		}
	}

	@Override
	public int compareTo(ProofObligation other)
	{
		return number - other.number;
	}

	public void setCheckedExpression(TCExpression checkedExpression)
	{
		this.checkedExpression = checkedExpression;
	}
	
	public TCExpression getCheckedExpression()
	{
		return checkedExpression;
	}
	
	/**
	 * Fully qualify a type name, if it is not declared local to the PO.
	 */
	protected String explicitType(TCType type, LexLocation poLoc)
	{
		if (type instanceof TCNamedType)
		{
			TCNamedType ntype = (TCNamedType)type;
			
			if (ntype.typename.getLocation().module.equals(poLoc.module))
			{
				return ntype.toString();
			}
			else
			{
				return ntype.typename.getExplicit(true).toString();
			}
		}
		else
		{
			return type.toString();
		}
	}
}
