/**
 * 
 */
package com.soffid.iam.sync.engine.cron;

import java.sql.SQLException;

import com.soffid.iam.ServiceLocator;
import com.soffid.iam.api.ScheduledTask;
import com.soffid.iam.service.TaskHandler;
import com.soffid.iam.sync.engine.DispatcherHandler;
import com.soffid.iam.sync.engine.db.ConnectionPool;
import com.soffid.iam.sync.service.TaskGenerator;

import es.caib.seycon.ng.exception.InternalErrorException;
import es.caib.seycon.ng.servei.InternalPasswordService;

/**
 * @author bubu
 *
 */
public class ReconcileAgentTask implements TaskHandler
{
	
	
	private ScheduledTask task;

	/* (non-Javadoc)
	 * @see java.lang.Runnable#run()
	 */
	public void run () throws SQLException, InternalErrorException
	{
		TaskGenerator tg = ServiceLocator.instance().getTaskGenerator();
		boolean found = false;
		for (DispatcherHandler dispatcher: tg.getDispatchers())
		{
			if (dispatcher.getSystem().getId().toString().equals (task.getParams()))
			{
				found = true;
				dispatcher.doReconcile(task);
			}
		}
		if (!found)
			task.getLastLog(). append(String.format("Dispatcher with id %s not found", task.getParams()));
	}

	/* (non-Javadoc)
	 * @see com.soffid.iam.service.TaskHandler#setTask(com.soffid.iam.api.ScheduledTask)
	 */
	public void setTask (ScheduledTask task)
	{
		this.task = task;
	}

	/* (non-Javadoc)
	 * @see com.soffid.iam.service.TaskHandler#getTask()
	 */
	public ScheduledTask getTask ()
	{
		return task;
	}

}
