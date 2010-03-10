/*
 * JBoss, Home of Professional Open Source.
 * See the COPYRIGHT.txt file distributed with this work for information
 * regarding copyright ownership.  Some portions may be licensed
 * to Red Hat, Inc. under one or more contributor license agreements.
 * 
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 * 
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 * 
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA
 * 02110-1301 USA.
 */

/**
 * 
 */
package org.teiid.transport;

import java.io.Serializable;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.ExecutionException;

import javax.crypto.SealedObject;

import org.teiid.adminapi.AdminProcessingException;
import org.teiid.dqp.internal.process.DQPWorkContext;
import org.teiid.runtime.RuntimePlugin;
import org.teiid.transport.ClientServiceRegistryImpl.ClientService;

import com.metamatrix.api.exception.ExceptionHolder;
import com.metamatrix.api.exception.MetaMatrixProcessingException;
import com.metamatrix.common.comm.api.Message;
import com.metamatrix.common.comm.platform.socket.client.ServiceInvocationStruct;
import com.metamatrix.common.log.LogManager;
import com.metamatrix.common.util.LogConstants;
import com.metamatrix.common.util.crypto.CryptoException;
import com.metamatrix.core.MetaMatrixRuntimeException;
import com.metamatrix.dqp.client.ResultsFuture;
import com.metamatrix.platform.security.api.SessionToken;

public class ServerWorkItem {
	
	private final ClientInstance socketClientInstance;
	private final Serializable messageKey;
    private final Message message;
    private final ClientServiceRegistryImpl csr;
    
    public ServerWorkItem(ClientInstance socketClientInstance, Serializable messageKey, Message message, ClientServiceRegistryImpl server) {
		this.socketClientInstance = socketClientInstance;
		this.messageKey = messageKey;
		this.message = message;
		this.csr = server;
	}

	/**
	 * main entry point for remote method calls.
	 */
	public void process() {
		Message result = null;
		String loggingContext = null;
		DQPWorkContext.setWorkContext(this.socketClientInstance.getWorkContext());
		final boolean encrypt = message.getContents() instanceof SealedObject;
        try {
            message.setContents(this.socketClientInstance.getCryptor().unsealObject(message.getContents()));
			if (!(message.getContents() instanceof ServiceInvocationStruct)) {
				throw new AssertionError("unknown message contents"); //$NON-NLS-1$
			}
			final ServiceInvocationStruct serviceStruct = (ServiceInvocationStruct)message.getContents();
			final ClientService clientService = this.csr.getClientService(serviceStruct.targetClass);			
			loggingContext = clientService.getLoggingContext();
			SessionToken.setSession(this.socketClientInstance.getWorkContext().getSessionToken());
			Method m = clientService.getReflectionHelper().findBestMethodOnTarget(serviceStruct.methodName, serviceStruct.args);
			Object methodResult;
			try {
				methodResult = m.invoke(clientService.getInstance(), serviceStruct.args);
			} catch (InvocationTargetException e) {
				throw e.getCause();
			}
			if (ResultsFuture.class.isAssignableFrom(m.getReturnType()) && methodResult != null) {
				ResultsFuture<Serializable> future = (ResultsFuture<Serializable>) methodResult;
				future.addCompletionListener(new ResultsFuture.CompletionListener<Serializable>() {

							public void onCompletion(
									ResultsFuture<Serializable> completedFuture) {
								Message asynchResult = new Message();
								try {
									asynchResult.setContents(completedFuture.get());
								} catch (InterruptedException e) {
									asynchResult.setContents(processException(e, clientService.getLoggingContext()));
								} catch (ExecutionException e) {
									asynchResult.setContents(processException(e.getCause(), clientService.getLoggingContext()));
								}
								sendResult(asynchResult, encrypt);
							}

						});
			} else { // synch call
				Message resultHolder = new Message();
				resultHolder.setContents((Serializable)methodResult);
				result = resultHolder;
			}
		} catch (Throwable t) {
			Message holder = new Message();
			holder.setContents(processException(t, loggingContext));
			result = holder;
		} finally {
			DQPWorkContext.releaseWorkContext();
			SessionToken.setSession(null);
		}
		
		if (result != null) {
			sendResult(result, encrypt);
		}
	}

	void sendResult(Message result, boolean encrypt) {
		if (encrypt) {
			try {
				result.setContents(socketClientInstance.getCryptor().sealObject(result.getContents()));
			} catch (CryptoException e) {
				throw new MetaMatrixRuntimeException(e);
			}
		}
		socketClientInstance.send(result, messageKey);
	}

	private Serializable processException(Throwable e, String context) {
		if (context == null) {
			context = LogConstants.CTX_TRANSPORT;
		}
		// Case 5558: Differentiate between system level errors and
		// processing errors. Only log system level errors as errors,
		// log the processing errors as warnings only
		if (e instanceof MetaMatrixProcessingException) {
        	logProcessingException(e, context);
		} else if (e instanceof AdminProcessingException) {
			logProcessingException(e, context);
		} else {
			LogManager.logError(context, e, RuntimePlugin.Util.getString("ServerWorkItem.Received_exception_processing_request", this.socketClientInstance.getWorkContext().getConnectionID())); //$NON-NLS-1$
		}

		return new ExceptionHolder(e);
	}
	
	private void logProcessingException(Throwable e, String context) {
		Throwable cause = e;
		while (cause.getCause() != null && cause != cause.getCause()) {
			cause = cause.getCause();
		}
		StackTraceElement elem = cause.getStackTrace()[0];
		LogManager.logDetail(context, e, "Processing exception for session", this.socketClientInstance.getWorkContext().getConnectionID()); //$NON-NLS-1$ 
		LogManager.logWarning(context, RuntimePlugin.Util.getString("ServerWorkItem.processing_error", e.getMessage(), this.socketClientInstance.getWorkContext().getConnectionID(), e.getClass().getName(), elem)); //$NON-NLS-1$
	}
}