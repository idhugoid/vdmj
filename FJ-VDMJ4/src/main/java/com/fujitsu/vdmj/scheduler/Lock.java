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
 *
 ******************************************************************************/

package com.fujitsu.vdmj.scheduler;

import java.io.Serializable;
import java.util.HashSet;
import java.util.Set;

import com.fujitsu.vdmj.lex.LexLocation;
import com.fujitsu.vdmj.messages.InternalException;
import com.fujitsu.vdmj.runtime.Context;

public class Lock implements Serializable
{
    private static final long serialVersionUID = 1L;
	private SchedulableThread lockedBy = null;
	private Set<SchedulableThread> waiters = new HashSet<SchedulableThread>();

	public void reset()
	{
		lockedBy = null;
		waiters.clear();
	}

	public void lock(Context ctxt, LexLocation location)
	{
		SchedulableThread th = (SchedulableThread)Thread.currentThread();

		while (lockedBy != null && lockedBy != th)
		{
			synchronized (waiters)
			{
				waiters.add(th);
			}

			try
			{
				th.locking(ctxt, location);
			}
			finally
			{
				synchronized (waiters)
				{
					waiters.remove(th);
				}
			}
		}

		lockedBy = th;
	}

	public void block(Context ctxt, LexLocation location)
	{
		SchedulableThread th = (SchedulableThread)Thread.currentThread();

		if (lockedBy != null && lockedBy != th)
		{
			throw new InternalException(65, "Illegal Lock state");
		}

		lockedBy = null;	// Unlocked, so signal locking waiters

		synchronized (waiters)
		{
			for (SchedulableThread w: waiters)
			{
				if (w.getRunState() == RunState.LOCKING)
				{
					w.setState(RunState.RUNNABLE);
				}
			}
		}

		do
		{
			synchronized (waiters)
			{
				waiters.add(th);
			}

			try
			{
				th.waiting(ctxt, location);
			}
			finally
			{
				synchronized (waiters)
				{
					waiters.remove(th);
				}
			}
		}
		while (lockedBy != null);

		lockedBy = th;
	}

	public void signal()
	{
		signalAll();
	}

	public void unlock()
	{
		SchedulableThread th = (SchedulableThread)Thread.currentThread();

		if (lockedBy != null && lockedBy != th)
		{
			throw new InternalException(65, "Illegal Lock state");
		}

		lockedBy = null;
		signalAll();
	}

	private void signalAll()
	{
		synchronized (waiters)
		{
			for (SchedulableThread th: waiters)
			{
				th.setState(RunState.RUNNABLE);
			}
		}
	}
}
