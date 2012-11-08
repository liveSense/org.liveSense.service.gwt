package org.liveSense.service.gwt;

import java.util.HashMap;
import java.util.Map;

import org.liveSense.core.service.OSGIClassLoaderManager;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.ServiceReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.web.bindery.requestfactory.shared.ServiceLocator;

public class OsgiServiceLocator implements ServiceLocator {

	static Logger log = LoggerFactory.getLogger(OsgiServiceLocator.class);
	// Caching services
	static Map<String, Object> serviceCache = new HashMap<String, Object>();
	static OSGIClassLoaderManager dynamicClassLoaderManager = null;
	static ClassLoader dynamicClassLoader = OsgiServiceLocator.class.getClassLoader(); 
	{
		BundleContext context = null;
		try {
			context = FrameworkUtil.getBundle(OSGIClassLoaderManager.class).getBundleContext();
		} catch (Throwable th) {
		}
		if (context != null) {
			dynamicClassLoaderManager = (OSGIClassLoaderManager) context.getService(context.getServiceReference(OSGIClassLoaderManager.class.getName()));
			if (dynamicClassLoaderManager != null)
				dynamicClassLoader = dynamicClassLoaderManager.getPackageAdminClassLoader(null);
		}
	}

	/**
	 * Get DynamicClassLoader manager instance from OSGi
	 * @return DynamicClassLoaderManager instance
	 */
//	public static OSGIClassLoaderManager getDynamicClassLoaderManager() {
//		return dynamicClassLoader;
//	}

	public static Object getInstance(String className) {
		if (className != null && !"".equals(className)) {
			// If the service is not presented in cache
			if (serviceCache.get(className) == null) {
				Class<?> clazz = null;
				try {
					clazz = dynamicClassLoader.loadClass(className);
				} catch (ClassNotFoundException e) {
					log.error("Could not load interface implementation from OSGi, interface not found: "+className);
				}

				if (clazz != null) {
					Bundle bundle = FrameworkUtil.getBundle(clazz);
					if (bundle != null) {
						try {
							ServiceReference ref = bundle.getBundleContext().getServiceReference(clazz.getName());
							if (ref != null) {
								serviceCache.put(className, bundle.getBundleContext().getService(ref));
							} else {
								log.info("OSGi reference does not found, creating class: "+clazz.getName());
								// We try to create. We don't cache
								try {
									return clazz.newInstance();
								} catch (InstantiationException e) {
									throw new RuntimeException(e);
								} catch (IllegalAccessException e) {
									throw new RuntimeException(e);
								}
							}
						} catch (Throwable th) {
							log.error("Could not get service reference for clazz: "+clazz.getName(), th);
						}
					}

				}
			}
			return serviceCache.get(className);
		}
		return null;
	}

	public Object getInstance(Class<?> clazz) {
		if (clazz != null) {
			return getInstance(clazz.getName());
		}
		return null;
	}

	public static void removeFromCache(Class<?> clazz) {
		serviceCache.remove(clazz);
	}

	public static void addToCache(Class<?> clazz, Object obj) {
		serviceCache.put(clazz.getName(), obj);
	}

	public static Object getFromCache(Class<?> clazz) {
		return serviceCache.get(clazz.getName());
	}

}