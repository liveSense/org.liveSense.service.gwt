/*
 *  Copyright 2010 Robert Csakany <robson@semmi.se>.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *  under the License.
 */
package org.liveSense.service.gwt;
/**
 *
 * @author Robert Csakany (robson@semmi.se)
 * @created Jan 04, 2011
 */
import com.google.gwt.user.server.rpc.RPCServletUtils;
import com.google.web.bindery.autobean.shared.AutoBean;
import com.google.web.bindery.autobean.shared.AutoBeanCodex;
import com.google.web.bindery.autobean.vm.AutoBeanFactorySource;
import com.google.web.bindery.requestfactory.server.Logging;
import com.google.web.bindery.requestfactory.server.RequestFactoryServlet;
import com.google.web.bindery.requestfactory.server.ServiceLayer;
import com.google.web.bindery.requestfactory.server.SimpleRequestProcessor;
import com.google.web.bindery.requestfactory.shared.RequestFactory;
import com.google.web.bindery.requestfactory.shared.ServerFailure;
import com.google.web.bindery.requestfactory.shared.ServiceLocator;
import com.google.web.bindery.requestfactory.shared.messages.MessageFactory;
import com.google.web.bindery.requestfactory.shared.messages.ResponseMessage;
import com.google.web.bindery.requestfactory.shared.messages.ServerFailureMessage;
import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.Reference;
import org.apache.felix.scr.annotations.ReferenceCardinality;
import org.apache.felix.scr.annotations.ReferencePolicy;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.apache.sling.auth.core.AuthenticationSupport;
import org.apache.sling.auth.core.spi.AuthenticationInfo;
import org.apache.sling.jcr.api.SlingRepository;
import org.liveSense.core.ClassInstanceCache;
import org.liveSense.core.CompositeClassLoader;
import org.liveSense.core.Configurator;
import org.liveSense.core.service.OSGIClassLoaderManager;
import org.liveSense.misc.jcrWrapper.RequestWrapper;
import org.liveSense.service.gwt.exceptions.AccessDeniedException;
import org.liveSense.service.gwt.exceptions.InternalException;
import org.osgi.framework.Bundle;
import org.osgi.service.packageadmin.PackageAdmin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jcr.*;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;
import java.text.MessageFormat;
import java.util.HashMap;
import java.util.Locale;
import java.util.ResourceBundle;

/**
 * Extending google's request factory servlet
 * <p/>
 * This class is for version 2.3.0 of the GWT and it is highly recommended to compile
 * client apps with the corresponding 2.3.0 GWT compiler only!
 * <p/>
 * GWT request factory servlets that are used in sling are required to extend the <code>SlingRemoteServiceServlet</code>
 * instead of google's own <code>RequestFactoryServlet</code>.
 * <p/>
 * It is important that any clientBundle using the Sling GWT Servlet Library imports the required packages from this clientBundle,
 * for otherwise the calls will fail due to well hidden <code>ClassNotFoundException</code>s. The client app will in
 * such a case only report "This application is outdated, please hit refresh...". As such, import the following
 * packages:
 * <p/>
 * <code>
 * com.google.gwt.user.server.*;
*  com.google.web.bindery.*;
 * </code>
 */

@Component(componentAbstract=true)
public abstract class GWTRequestFactoryServlet extends HttpServlet {

	/**
	 * 
	 */
	private static final long serialVersionUID = 2565545902953291699L;
	/**
	 * default log
	 */
	private final Logger log = LoggerFactory.getLogger(GWTRequestFactoryServlet.class);

	public static final Logger payloadLogger = LoggerFactory.getLogger("GWTREQUESTFACTORY");
	
	@Reference(cardinality=ReferenceCardinality.MANDATORY_UNARY, policy=ReferencePolicy.DYNAMIC)
	private Configurator config;
	
	@Reference(cardinality=ReferenceCardinality.MANDATORY_UNARY, policy=ReferencePolicy.DYNAMIC)
	SlingRepository repository;

	@Reference(cardinality=ReferenceCardinality.MANDATORY_UNARY, policy=ReferencePolicy.DYNAMIC)
	PackageAdmin packageAdmin;
	
	@Reference(cardinality=ReferenceCardinality.MANDATORY_UNARY, policy=ReferencePolicy.DYNAMIC)
	AuthenticationSupport authenticationSupport;
	
	@Reference(cardinality=ReferenceCardinality.MANDATORY_UNARY, policy=ReferencePolicy.DYNAMIC)
	ResourceResolverFactory resourceResolverFactory;
	
	@Reference(cardinality=ReferenceCardinality.MANDATORY_UNARY, policy=ReferencePolicy.DYNAMIC)
	protected OSGIClassLoaderManager dynamicClassLoaderManager;

	@Reference(cardinality=ReferenceCardinality.MANDATORY_UNARY, policy=ReferencePolicy.DYNAMIC)
	ServiceLocator serviceLocator;

	@Reference(cardinality=ReferenceCardinality.MANDATORY_UNARY, policy=ReferencePolicy.DYNAMIC)
	ClassInstanceCache instanceCache;

	private static final String JSON_CHARSET = "UTF-8";
	private static final String JSON_CONTENT_TYPE = "application/json";
	
	/**
	 * These ThreadLocals are used to allow service objects to obtain access to
	 * the HTTP transaction.
	 */
	private static final ThreadLocal<ServletContext> perThreadContext = new ThreadLocal<ServletContext>();
	private static final ThreadLocal<HttpServletRequest> perThreadRequest = new ThreadLocal<HttpServletRequest>();
	private static final ThreadLocal<HttpServletResponse> perThreadResponse = new ThreadLocal<HttpServletResponse>();

	/**
	 * Returns the thread-local {@link HttpServletRequest}.
	 * 
	 * @return an {@link HttpServletRequest} instance
	 */
	public static HttpServletRequest getThreadLocalRequest() {
		return perThreadRequest.get();
	}

	/**
	 * Returns the thread-local {@link HttpServletResponse}.
	 * 
	 * @return an {@link HttpServletResponse} instance
	 */
	public static HttpServletResponse getThreadLocalResponse() {
		return perThreadResponse.get();
	}

	/**
	 * Returns the thread-local {@link ServletContext}
	 * 
	 * @return the {@link ServletContext} associated with this servlet
	 */
	public static ServletContext getThreadLocalServletContext() {
		return perThreadContext.get();
	}

	protected SimpleRequestProcessor processor;

	/**
	 * @return the defaultExceptionHandler
	 */
	public static DefaultExceptionHandler getDefaultExceptionHandler() {
		return defaultExceptionHandler;
	}

	/**
	 * @param defaultExceptionHandler the defaultExceptionHandler to set
	 */
	public static void setDefaultExceptionHandler(DefaultExceptionHandler defaultExceptionHandler) {
		GWTRequestFactoryServlet.defaultExceptionHandler = defaultExceptionHandler;
	}

	private static DefaultExceptionHandler defaultExceptionHandler = new DefaultExceptionHandler();
	
	/**
	 * Constructs a new {@link RequestFactoryServlet} with a
	 * {@code DefaultExceptionHandler}.
	 */
	public GWTRequestFactoryServlet() {

	}
	
	protected void initOsgiProcessor() {
		// Set google serviceLayerCache off
		System.setProperty("gwt.rf.ServiceLayerCache", new Boolean(false).toString());
		
		getDefaultExceptionHandler().setRequestFactoryServlet(this);
		ClassLoader old = Thread.currentThread().getContextClassLoader();
		Thread.currentThread().setContextClassLoader(this.getClass().getClassLoader());
		processor = new SimpleRequestProcessor(ServiceLayer.create(new OsgiServiceLayerDecorator(dynamicClassLoaderManager.getPackageAdminClassLoader(null), serviceLocator, instanceCache)));
		processor.setExceptionHandler(getDefaultExceptionHandler());
		Thread.currentThread().setContextClassLoader(old);
	}

	private void ensureConfig() {
		String symbolMapsDirectory = getServletConfig().getInitParameter("symbolMapsDirectory");
		if (symbolMapsDirectory != null) {
			Logging.setSymbolMapsDirectory(symbolMapsDirectory);
		}
	}
	
	private final HashMap<String, ClassLoader> classLoaders = new HashMap<String, ClassLoader>();


    /**
     *
     * Allows the extending OSGi service to set its classloader.
     *
     * @param classLoader The classloader to provide to the SlingRemoteServiceServlet.
     */
    protected void setClassLoader(ClassLoader classLoader) {
        //this.classLoader = classLoader;
    	classLoaders.put(classLoader.toString(), classLoader);
    }


	public abstract void callInit() throws Throwable;

	public abstract void callFinal() throws Throwable;
	
	public abstract ServerFailure failure(Throwable throwable);
	
	static final MessageFactory FACTORY = AutoBeanFactorySource.create(MessageFactory.class);
    
	private AutoBean<ServerFailureMessage> createFailureMessage(Throwable throwable) {	
		ServerFailure failure = defaultExceptionHandler.createServerFailure(throwable);
		AutoBean<ServerFailureMessage> bean = FACTORY.failure();
		ServerFailureMessage msg = bean.as();
		msg.setExceptionType(failure.getExceptionType());
		msg.setMessage(failure.getMessage());
		msg.setStackTrace(failure.getStackTraceString());
		msg.setFatal(failure.isFatal());
		
		return bean;
	}


	/**
     * Process exception and convert it to JSON object
     * @param phase
     * @param payload
     * @param e
     * @return
     */
    private String processException(String phase, String payload, Throwable e) {

        AutoBean<ResponseMessage> responseBean = FACTORY.response();
        // Create a new response envelope, since the state is unknown
        responseBean = FACTORY.response();
        responseBean.as().setGeneralFailure(createFailureMessage(e).as());
        String ret = AutoBeanCodex.encode(responseBean).getPayload();
        // Return a JSON-formatted payload
        return ret;
    }


	/**
	 * Processes a POST to the server.
	 * 
	 * @param request an {@link HttpServletRequest} instance
	 * @param response an {@link HttpServletResponse} instance
	 * @throws IOException if an internal I/O error occurs
	 * @throws ServletException if an error occurs in the servlet
	 */
	@Override
	protected void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException,
			ServletException {

        ClassLoader oldClassLoader = null;
        boolean customClassloader =false;

        // Inititalizing threadLocal variables
		perThreadContext.set(getServletContext());
		perThreadRequest.set(request);
		perThreadResponse.set(response);

        // Custom classloader - OSGi context
        if (!classLoaders.isEmpty()) {
        	customClassloader = true;
        }
        	

        if (customClassloader) {
        	// Backup old contextClassLoader
        	oldClassLoader = Thread.currentThread().getContextClassLoader();

            // Generating composite classloader from map
            CompositeClassLoader cClassLoader = new CompositeClassLoader();
            for (String key : classLoaders.keySet()) {
                cClassLoader.add(classLoaders.get(key));            	
            }
            
            // Set contextClassLoader
            Thread.currentThread().setContextClassLoader(cClassLoader);
        }    
        
        String payload = null;
        String jsonRequestString = null;
        boolean error = false;
        boolean callInitExecuted = false;

        try {
        	try {
				ensureConfig();
				jsonRequestString = RPCServletUtils.readContent(request, JSON_CONTENT_TYPE, JSON_CHARSET);
        	} catch (Throwable e) {
				error = true;
				payload = processException("processRuntimeException", payload, e);
            	payloadLogger.error ("<<< (process) User: "+getUser()+" Payload: "+payload);
				log.error("Unexpected error on RequestFactory doPost init", e);
        	}

        	
        	// Authenticating - OSGi context
            if (authenticationSupport != null && !error) {
	            try {
	            	authenticationSupport.handleSecurity(getThreadLocalRequest(), getThreadLocalResponse());
	            } catch (Throwable e) {
	            	error = true;
	            	payload = processException("handleSecurity", jsonRequestString, e);
	            }
            }
                        
            // CallInit
            if (!error) {
	            try {
	            	callInit();
	            	callInitExecuted = true;
	            } catch (Throwable e) {
	            	error = true;
	            	payload = processException("callInit", jsonRequestString, e);
                	payloadLogger.error ("<<< (callInit) User: "+getUser()+" Payload: "+payload, e);
	            }
            }
            
            // Process the request
            if (!error) {
    			try {
                	payloadLogger.info (">>> (process) User: "+getUser()+" Payload: "+jsonRequestString);
    				payload = processor.process(jsonRequestString);
    				response.setStatus(HttpServletResponse.SC_OK);
    				response.setContentType(RequestFactory.JSON_CONTENT_TYPE_UTF8);
                	payloadLogger.info ("<<< (process) User: "+getUser()+" Payload: "+payload);
    			} catch (Throwable e) {
    				error = true;
    				payload = processException("processRuntimeException", payload, e);
                	payloadLogger.error ("<<< (process) User: "+getUser()+" Payload: "+payload, e);
    				log.error("Unexpected error", e);
    			}
            }
            	
           	// callFinal
            if (callInitExecuted) {
				try {
					callFinal();
				} catch (Throwable e) {
					payload = processException("callFinal", payload, e);
					payloadLogger.error ("<<< (callFinal) User: "+getUser()+" Payload: "+payload, e);
				}
            }
        } catch (Throwable e) {
			payload = processException("processRuntimeException", payload, e);
        	payloadLogger.error ("<<< (process) User: "+getUser()+" Payload: "+payload, e);
			log.error("Unexpected error", e);
        } finally {
        	if (customClassloader) {
            	Thread.currentThread().setContextClassLoader(oldClassLoader);
            }

        	// The Writer must be obtained after setting the content type
			perThreadContext.set(null);
			perThreadRequest.set(null);
			perThreadResponse.set(null);
			response.setCharacterEncoding(config.getEncoding());
        	PrintWriter writer = response.getWriter();
			writer.print(payload);
			writer.flush();
        }
	}
    	
	protected RequestWrapper getRequest() {
		return new RequestWrapper(this.getThreadLocalRequest(), null);
	}

	protected String getUser() {
		return (String)this.getThreadLocalRequest().getAttribute("org.osgi.service.http.authentication.remote.user");
	}
	
	public Bundle getBundleByName(String name) {
		 Bundle[] ret = packageAdmin.getBundles(name, null);
		 if (ret != null && ret.length > 0) {
			 return ret[0];
		 }
		 return null;
	}

	protected Locale getLocale() {
		if (getThreadLocalRequest().getAttribute("locale") == null) {
			RequestWrapper rw = new RequestWrapper(getThreadLocalRequest(), config.getDefaultLocale());
			getThreadLocalRequest().setAttribute("locale", rw.getLocale());
			return rw.getLocale();
		} else {
			return getThreadLocalRequest().getLocale();
		}
	}
	
	protected void setLocale(Locale locale) {
		getThreadLocalRequest().setAttribute("locale", locale);		
	}
	
	public String formatMessage(String key, Object[] args) {
		String message = getResourceBundle().getString(key);
		return MessageFormat.format(message, args);
	}
	
	protected Session getUserSession(Repository repository) throws AccessDeniedException, InternalException {
		try {
			AuthenticationInfo info = (AuthenticationInfo) this.getThreadLocalRequest().getAttribute("org.apache.sling.commons.auth.spi.AuthenticationInfo");
            return repository.login(new SimpleCredentials(getUser(), info.getPassword()));
		} catch (LoginException ex) {
			throw new AccessDeniedException(formatMessage("accessDeniedForUser",new Object[]{getUser(), ex}));
		} catch (RepositoryException ex) {
			throw new InternalException(formatMessage("repositoryException", new Object[]{ex}));
		}
	}

	public ResourceBundle getResourceBundle() {
		return (ResourceBundle)getThreadLocalRequest().getAttribute("resourceBundle");
	}
	
	public void setResourceBundle(
		ResourceBundle resourceBundle) {
		getThreadLocalRequest().setAttribute("resourceBundle", resourceBundle);
	
	}

	public SlingRepository getRepository() {
		return repository;
	}

	public void setRepository(SlingRepository repository) {
		this.repository = repository;
	}

	public Configurator getConfig() {
		return config;
	}

	public void setConfig(Configurator config) {
		this.config = config;
	}

	public PackageAdmin getPackageAdmin() {
		return packageAdmin;
	}

	public void setPackageAdmin(PackageAdmin packageAdmin) {
		this.packageAdmin = packageAdmin;
	}

	public ResourceResolverFactory getResourceResolverFactory() {
		return resourceResolverFactory;
	}

	public void setResourceResolverFactory(
			ResourceResolverFactory resourceResolverFactory) {
		this.resourceResolverFactory = resourceResolverFactory;
	}

	public AuthenticationSupport getAuthenticationSupport() {
		return authenticationSupport;
	}

	public void setAuthenticationSupport(AuthenticationSupport authenticationSupport) {
		this.authenticationSupport = authenticationSupport;
	}		
	
}
