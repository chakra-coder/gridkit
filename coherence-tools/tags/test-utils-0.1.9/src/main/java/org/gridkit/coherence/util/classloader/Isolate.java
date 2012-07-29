/**
 * Copyright 2011 Grid Dynamics Consulting Services, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.gridkit.coherence.util.classloader;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.ObjectStreamClass;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.Reader;
import java.io.Serializable;
import java.io.Writer;
import java.lang.Thread.State;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.net.DatagramSocket;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.security.AllPermission;
import java.security.CodeSource;
import java.security.PermissionCollection;
import java.security.Permissions;
import java.security.ProtectionDomain;
import java.security.cert.Certificate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.InvalidPropertiesFormatException;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Properties;
import java.util.Set;
import java.util.Vector;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.TimeUnit;
import java.util.logging.LogManager;

import org.junit.Ignore;

/**
 *	@author Alexey Ragozin (alexey.ragozin@gmail.com)
 */
@Ignore
public class Isolate {
	
	private static final InheritableThreadLocal<Isolate> ISOLATE = new InheritableThreadLocal<Isolate>();
	
	private static PrintStream rootOut;
	private static PrintStream rootErr;
	private static Properties rootProperties;
	
	static {
		
		// initializing LogManager outside of isolate
		LogManager.getLogManager();
		
		System.err.println("Installing java.lang.System multiplexor");
		
		rootOut = System.out;
		rootErr = System.err;
		rootProperties = System.getProperties();
		
		PrintStream mOut = new PrintStreamMultiplexor() {
			@Override
			protected PrintStream resolve() {
				Isolate i = ISOLATE.get();
				if (i == null) {
					return rootOut;
				}
				else {
					return i.stdOut;
				}
			}
		};

		PrintStream mErr = new PrintStreamMultiplexor() {
			@Override
			protected PrintStream resolve() {
				Isolate i = ISOLATE.get();
				if (i == null) {
					return rootErr;
				}
				else {
					return i.stdErr;
				}
			}
		};
		
		@SuppressWarnings("serial")
		Properties mProps = new PropertiesMultiplexor() {
			@Override
			protected Properties resolve() {
				Isolate i = ISOLATE.get();
				if (i == null) {
					return rootProperties;
				}
				else {
					return i.sysProps;
				}
			}
		};
		
		System.setOut(mOut);
		System.setErr(mErr);
		System.setProperties(mProps);
	}

	public static Isolate currentIsolate() {
		return ISOLATE.get();
	}
	
	private static final Map<Class<?>, Object> PRIMITIVE_DEFAULTS = new HashMap<Class<?>, Object>();
	static {
		PRIMITIVE_DEFAULTS.put(boolean.class, Boolean.FALSE);
		PRIMITIVE_DEFAULTS.put(byte.class, Byte.valueOf((byte)0));
		PRIMITIVE_DEFAULTS.put(short.class, Short.valueOf((byte)0));
		PRIMITIVE_DEFAULTS.put(char.class, Character.valueOf((char)0));
		PRIMITIVE_DEFAULTS.put(int.class, Integer.valueOf((char)0));
		PRIMITIVE_DEFAULTS.put(long.class, Long.valueOf((char)0));
		PRIMITIVE_DEFAULTS.put(float.class, Float.valueOf(0f));
		PRIMITIVE_DEFAULTS.put(double.class, Double.valueOf(0f));
	}

	
	private String name;
	private Thread isolatedThread;
	private IsolatedClassloader cl;
	
	private PrintStream stdOut;
	private PrintStream stdErr;
	private WrapperPrintStream wrpOut;
	private WrapperPrintStream wrpErr;
	private Properties sysProps;
	private int shutdownRetry = 0;
	
	private ThreadGroup threadGroup;
	
	private volatile BlockingQueue<WorkUnit> queue;
	
	public Isolate(String name, String... packages) {		
		this.name = name;
		this.cl = new IsolatedClassloader(getClass().getClassLoader(), packages);

		threadGroup = new IsolateThreadGroup(name);
		
		sysProps = new Properties();
		sysProps.putAll(System.getProperties());
		sysProps.put("isolate.name", name);
		
		wrpOut = new WrapperPrintStream("[" + name + "] ", rootOut);
		stdOut = new PrintStream(wrpOut);
		wrpErr = new WrapperPrintStream("[" + name + "] ", rootErr);
		stdErr = new PrintStream(wrpErr);
	}
	
	public String getName() {
		return name;
	}
	
	public synchronized void setName(String name) {
		this.name = name;
		// reinit name stuff
		// WARN threadGroup name will not be changed
		this.wrpOut.setPrefix("[" + name + "] ");
		this.wrpErr.setPrefix("[" + name + "] ");
		if (isolatedThread != null) {
			isolatedThread.setName("Isolate-" + name);
		}
	}
	
	public synchronized void start() {
		isolatedThread = new Thread(threadGroup, new Runner());
		isolatedThread.setName("Isolate-" + name);
		isolatedThread.setDaemon(true);
		queue = new SynchronousQueue<Isolate.WorkUnit>();
		isolatedThread.start();		
	}
	
	public void exclude(Class<?>... excludes) {
		cl.exclude(excludes);
	}

	public void removeFromClasspath(URL basePath) {
		cl.removeFromClasspath(basePath);
	}

	public void addToClasspath(URL path) {
		cl.addToClasspath(path);
	}

	public String getProp(String prop) {
		return sysProps.getProperty(prop);
	}

	public void setProp(String prop, String value) {
		sysProps.setProperty(prop, value);
	}
	
	public void setProp(Map<String, String> props) {
		sysProps.putAll(props);
	}

	private void abortablePut(WorkUnit wu) throws InterruptedException {
		while(true) {
			try {
				if (queue.offer(wu, 100, TimeUnit.MILLISECONDS)) {
					break;
				}
			} catch (InterruptedException e) {
				throw e;
			} catch (RuntimeException e) {
				throw e;
			} catch (Exception e) {
				// most likely JUnit is trying to kill us
				stdErr.println("Stoping isolate due to stop signal: " + e.toString());
				stop();
				AnyThrow.throwUncheked(e);
			}			
		}
	}
	
	private Object process(WorkUnit wu) {
		try {
			abortablePut(wu);
		} catch (NullPointerException e) {
			throw new IllegalStateException("Isolate[" + name + "] is not started");
		} catch (InterruptedException e) {
			AnyThrow.throwUncheked(e);
		}
		try {
			abortablePut(NOP);
		} catch (NullPointerException e) {
			throw new IllegalStateException("Isolate[" + name + "] has been stopped");
		} catch (InterruptedException e) {
			e = convertOut(e);
			AnyThrow.throwUncheked(e);
		}
	
		Object result;
		if (wu instanceof CallableWorkUnit) {
			try {
				result = ((CallableWorkUnit<?>)wu).future.get();				
			}
			catch(ExecutionException e) {
				e = convertOut(e);
				weaveAndRethrow(e.getCause());
				return null;
			}
			catch(Throwable e) {
				e = convertOut(e);
				AnyThrow.throwUncheked(e);
				return null;
			}
			return result;
		}
		else {
			return null;
		}
	}
	
	private void weaveAndRethrow(Throwable e) {
		Exception d = new Exception();
		
		StackTraceElement marker = new StackTraceElement(Isolate.class.getName(), "", null, -1);
		StackTraceElement boundary = new StackTraceElement("<isolate-boundary>", "<exec>", name, -1);
		
		ExceptionWeaver.replaceStackTop(e, marker, d, marker, boundary);		
		AnyThrow.throwUncheked(e);		
	}

	private void proxyWeaveAndRethrow(Object proxy, Throwable e) {
		Exception d = new Exception();
		
		StackTraceElement marker1 = new StackTraceElement("", "", null, -2); // match native method
		StackTraceElement marker2 = new StackTraceElement(proxy.getClass().getName(), "", null, -1);
		StackTraceElement boundary = new StackTraceElement("<isolate-boundary>", "<proxy>", name, -1);
		
		ExceptionWeaver.replaceStackTop(e, marker1, d, marker2, boundary);		
		AnyThrow.throwUncheked(e);		
	}

	/**
	 * @deprecated Use {@link #exec(Runnable)} and normal object passing
	 */
	@Deprecated
	public void submit(Class<?> clazz, Object... constructorArgs) {
		process(new ClassWorkUnit(clazz.getName(), constructorArgs));
	}
	
	@SuppressWarnings("rawtypes")
	public void exec(Runnable task) {
		CallableWorkUnit wu = new CallableWorkUnit((Runnable) convertIn(task));
		process(wu);
	}

	@SuppressWarnings("unchecked")
	public <V> V exec(Callable<V> task) {
		CallableWorkUnit<V> wu = new CallableWorkUnit<V>((Callable<V>) convertIn(task));
		return (V) convertOut(process(wu));
	}

	@SuppressWarnings("unchecked")
	public <V> V export(Callable<V> task) {
		CallableWorkUnit<V> wu = new CallableWorkUnit<V>((Callable<V>) convertIn(task));
		return (V) exportOut(process(wu));
	}

	@SuppressWarnings("unchecked")
	public <V> V exportNoProxy(Callable<V> task) {
		CallableWorkUnit<V> wu = new CallableWorkUnit<V>((Callable<V>) convertIn(task));
		return (V) process(wu);
	}
	
	@SuppressWarnings("unchecked")
	protected <T> Object convertIn(T obj) {
		if (obj != null && !(obj instanceof Serializable) && obj.getClass().isAnonymousClass()) {
			try {
				return (T)convertAnonimous(obj);
			} catch (Exception e) {
				throw new RuntimeException(e);
			}
		}
		return fromBytes(toBytes(obj), cl);
	}

	@SuppressWarnings("unchecked")
	protected <V> V convertOut(Object obj) {
		return (V) fromBytes(toBytes(obj), cl.getParent());
	}
	
	protected Object exportOut(Object obj) {
		Class<?>[] interfaces = convertOut(collectInterfaces(obj.getClass()));
		ProxyOut po = new ProxyOut(obj, interfaces);
		return Proxy.newProxyInstance(Thread.currentThread().getContextClassLoader(), interfaces, po);		
	}
	
	@SuppressWarnings("rawtypes")
	protected Class[] collectInterfaces(Class<?> type) {
		Set<Class<?>> interfaces = new LinkedHashSet<Class<?>>();
		collectInterfaces(type, interfaces);
		return interfaces.toArray(new Class[interfaces.size()]);
	}
	
	private void collectInterfaces(Class<?> type, Set<Class<?>> collector) {
		if (type.isInterface()) {
			collector.add(type);
		}
		for(Class<?> i : type.getInterfaces()) {
			collectInterfaces(i,collector);
		}
	}

	protected boolean isIsolatedClass(Class<?> x) {
		ClassLoader cl = x.getClassLoader();
		while(cl != null) {
			if (cl == this.cl) {
				return true;
			}
			cl = cl.getParent();
		}
		return false;
	}
	
	@SuppressWarnings("rawtypes")
	protected Object convertAnonimous(Object obj) throws IllegalArgumentException, InstantiationException, IllegalAccessException, InvocationTargetException, ClassNotFoundException {
		Class c_out = obj.getClass();
		Class c_in = cl.loadClass(c_out.getName());
		
		if (c_in == c_out) {
			return obj;
		}
		else {
			Field[] f_out = collectFields(c_out);
			Field[] f_in = collectFields(c_in);
			Constructor<?> c = c_in.getDeclaredConstructors()[0];
			
			c.setAccessible(true);
			// we have to init primitive params, cause null cannot be converted to primitive value
			Object[] params = new Object[c.getParameterTypes().length];
			for(int i = 0; i != params.length; ++i) {
				Class<?> p = c.getParameterTypes()[i];
				params[i] = PRIMITIVE_DEFAULTS.get(p);
			}
			Object oo = c.newInstance(params);
			
			for(Field fo : f_out) {
				if (fo.getName().startsWith("this$")) {
					continue;
				}
				fo.setAccessible(true);
				Object v = fo.get(obj);
				for(Field fi : f_in) {
					if (fi.getName().equals(fo.getName()) && fi.getDeclaringClass().getName().equals(fo.getDeclaringClass().getName())) {
						fi.setAccessible(true);
						fi.set(oo, convertIn(v));
					}
				}
			}
			
			return oo;
		}
	}
	
	private Field[] collectFields(Class<?> c) {
		List<Field> result = new ArrayList<Field>();
		collectFields(result, c);
		return result.toArray(new Field[result.size()]);
	}
	
	private void collectFields(List<Field> result, Class<?> c) {
		Class<?> s = c.getSuperclass();
		if (s != Object.class) {
			collectFields(result, s);
		}
		for(Field f: c.getDeclaredFields()) {
			if (!Modifier.isStatic(f.getModifiers())) {
				result.add(f);
			}
		}
	}
	
	@SuppressWarnings("deprecation")
	public void suspend() {
		threadGroup.suspend();
	}

	@SuppressWarnings("deprecation")
	public void resume() {
		threadGroup.resume();
	}
	
	public void stop() {
		try {
			queue.offer(STOP, 1000, TimeUnit.SECONDS);
			while(queue != null) {
				Thread.sleep(50);
			}
		} catch (InterruptedException e) {
			e.printStackTrace();
		} catch (NullPointerException e) {
			// ignore;
		}
		stdErr.println("Stopping ...");
		while(true) {
			while( 0 < kill(threadGroup)  
					+ removeShutdownHooks()
					+ removeAppContexts() ) {
				try {
					Thread.sleep(10);
				} catch (InterruptedException e) {
				}
				++shutdownRetry;
			}
			try {
				if (!threadGroup.isDestroyed()) {
					threadGroup.destroy();
				}
				break;
			}
			catch(IllegalThreadStateException e) {
				stdErr.println(e);
			}
			if (shutdownRetry >  1000) {
				stdErr.println("Isolate clean up failed");
			}
		}
		cl = null;
		threadGroup = null;
		isolatedThread = null;
		sysProps = null;
		
		stdErr.println("Stopped");
		
		System.gc();
		Runtime.getRuntime().runFinalization();
	}
	
	@SuppressWarnings("deprecation")
	private int kill(ThreadGroup tg) {
		int threadCount = 0;
		
		Thread[] threads = new Thread[2 * tg.activeCount()];
		int n = tg.enumerate(threads);
		for(int i = 0; i != n; ++i) {
			++threadCount;
			Thread t = threads[i];
			if (Runtime.getRuntime().removeShutdownHook(t)) {
				stdErr.println("Removing shutdown hook: " + t.getName());
			}
			if (t.getState() != State.TERMINATED) {
				stdErr.println("Killing: " + t.getName());
				try { t.resume(); }	catch(Exception e) {/* ignore */};
				try { t.start(); }	catch(Exception e) {/* ignore */};
				try { t.interrupt(); }	catch(Exception e) {/* ignore */};
				try { t.stop(new ThreadDoomException()); }	catch(IllegalStateException e) {/* ignore */};				
			}
			else {
				if (shutdownRetry % 10 == 9) {
					stdErr.println("Already terminated: " + t.getName());
				}
			}
			
			if (t.isAlive() && shutdownRetry > 24) {
				if (shutdownRetry > 10 && (shutdownRetry % 10 == 5)) {
					StackTraceElement[] trace = t.getStackTrace();
					for(StackTraceElement e: trace) {
						stdErr.println("  at " + e);
					}
				}
				try {
					try { t.interrupt(); }	catch(Exception e) {/* ignore */};
					trySocketInterrupt(t);
//					tryStop(t);
					try { t.interrupt(); }	catch(Exception e) {/* ignore */};
					try { t.stop(new ThreadDoomException()); }	catch(IllegalStateException e) {/* ignore */};				
				}
				catch(Exception e) {
					stdErr.println("Socket interruption failed: " + e.toString());
				}
			}
		}
		
		ThreadGroup[] groups = new ThreadGroup[2 * tg.activeGroupCount()];
		n = tg.enumerate(groups);
		for(ThreadGroup g: groups) {
			if (g != null) {
				threadCount += kill(g);
			}
		}
		
		return threadCount;
	}
	
	private void trySocketInterrupt(Thread t) {
		Object target = getField(t, "target");
		if (target == null) {
			return;
		}
		String cn = target.getClass().getName();
		if (cn.startsWith("com.tangosol.coherence.component")
				&& cn.contains("PacketListener")) {
			try {
				Object udpSocket = getField(target, "__m_UdpSocket");
				DatagramSocket ds = (DatagramSocket) getField(udpSocket, "__m_DatagramSocket");
				ds.close();
				stdErr.println("Closing socket for " + t.getName());
			}
			catch(Exception e) {
				// ignore
			}
		}
		else if (cn.startsWith("com.tangosol.coherence.component")
					&& cn.contains("PacketPublisher")) {
			try {
				Object udpSocket = getField(target, "__m_UdpSocketUnicast");
				DatagramSocket ds = (DatagramSocket) getField(udpSocket, "__m_DatagramSocket");
				ds.close();
				stdErr.println("Closing socket for " + t.getName());
			}
			catch(Exception e) {
				// ignore;
			}
			try {
				Object udpSocket = getField(target, "__m_UdpSocketMulticast");
				DatagramSocket ds = (DatagramSocket) getField(udpSocket, "__m_DatagramSocket");
				ds.close();
				stdErr.println("Closing socket for " + t.getName());
			}
			catch(Exception e) {
				// ignore;
			}
		}
	}

	// TODO plugable thread killers
	@SuppressWarnings("unused")
	private void tryStop(Thread thread) {
		Object target = getField(thread, "target");
		if (target != null) {
			try {
				Method m = target.getClass().getMethod("stop");
				m.setAccessible(true);
				m.invoke(target);
				stdErr.println("Calling stop on " + thread.getName());
			} catch (Exception e) {
				//ignore
			}
		}
	}
	
	private static Object getField(Object x, String field) {
		try {
			Field f = null;
			Class<?> c = x.getClass();
			while(f == null && c != Object.class) {
				try {
					f = c.getDeclaredField(field);
				} catch (NoSuchFieldException e) {
				}
				if (f == null) {
					c = c.getSuperclass();
				}
			}
			if (f != null) {
				f.setAccessible(true);
				return f.get(x);
			}
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
		
		throw new IllegalArgumentException("Cannot get '" + field + "' from " + x.getClass().getName());
	}

	boolean isOwnedThreadGroup(ThreadGroup tg) {
		while(true) {
			if (tg == threadGroup) {
				return true;
			}
			else if (tg.getParent() == null || tg.getParent() == tg) {
				return false;
			}
			else {
				tg = tg.getParent();
			}
		}
	}
	
	private int removeShutdownHooks() {
		int threadCount = 0;
		for(Thread t : getSystemShutdownHooks()) {
			if (isOwnedThreadGroup(t.getThreadGroup()) || t.getContextClassLoader() == cl) {
				++threadCount;
				if (Runtime.getRuntime().removeShutdownHook(t)) {
					stdErr.println("Removing shutdown hook: " + t.getName());
				}
			}
		}
		return threadCount;
	}
	
	@SuppressWarnings("unchecked")
	private Collection<Thread> getSystemShutdownHooks() {
		try {
			Class<?> cls = Class.forName("java.lang.ApplicationShutdownHooks");
			Field f = cls.getDeclaredField("hooks");
			f.setAccessible(true);
			Map<Thread, Thread> hooks = (Map<Thread, Thread>) f.get(null);
			return new ArrayList<Thread>(hooks.values());
		} catch (Exception e) {
			e.printStackTrace();
			return Collections.emptyList();
		}
	}
	
	@SuppressWarnings("restriction")
	private int removeAppContexts() {
		int n = 0;
		Set<sun.awt.AppContext> contexts = sun.awt.AppContext.getAppContexts();
		Iterator<sun.awt.AppContext> it = contexts.iterator();
		while(it.hasNext()) {
			sun.awt.AppContext ctx = it.next();
			if (isOwnedThreadGroup(ctx.getThreadGroup())) {
				++n;
				it.remove();				
				stdErr.println("Removing AppContext: " + ctx.toString());
			}
		}
		return n;
	}
	
	public Class<?> loadClass(String name) throws ClassNotFoundException {
		return cl.loadClass(name);
	}
	
	public ClassLoader getClassLoader() {
		return cl;
	}

	@SuppressWarnings("serial")
	private class ThreadDoomException extends ThreadDeath {

//		@Override
//		public String getMessage() {
//			//System.out.println("OMEGA:getMessage");
//			throw this;
//		}

//		@Override
//		public String getLocalizedMessage() {
//			//System.out.println("OMEGA:getLocalizedMessage");
//			throw this;
//		}

		@Override
		public Throwable getCause() {
			//System.out.println("OMEGA:getCause");			
			return this;
		}

		@Override
		public String toString() {
			return "Isolate [" + name + "] has been terminated";
		}

		@Override
		public void printStackTrace() {
			//System.out.println("OMEGA:printStackTrace");
//			throw this;
		}

		@Override
		public void printStackTrace(PrintStream s) {
			//System.out.println("OMEGA:printStackTrace");
//			throw this;
		}

		@Override
		public void printStackTrace(PrintWriter s) {
			//System.out.println("OMEGA:printStackTrace");
//			throw this;
		}

		@Override
		public StackTraceElement[] getStackTrace() {
			//System.out.println("OMEGA:getStackTrace");
//			throw this;
			return null;
		}
	}
	
	private class IsolateThreadGroup extends ThreadGroup {
		
		private IsolateThreadGroup(String name) {
			super(name);
		}

		@Override
		public void uncaughtException(Thread t, Throwable e) {
			if (e instanceof ThreadDeath) {
				// ignore
			}
			else {
				stdErr.println("Uncaught exception at thread " + t.getName());
				e.printStackTrace(stdErr);
			}
		}
	}	

	private interface WorkUnit {		
		public void exec() throws Exception;
	}
	
	private static StopMarker STOP = new StopMarker();

	private static class StopMarker implements WorkUnit {
		@Override
		public void exec() {
			throw new UnsupportedOperationException();
		}
	}
	
	private static Nop NOP = new Nop();

	private static class Nop implements WorkUnit {
		@Override
		public void exec() {
			// nop
		}
	}
	
	private static class ClassWorkUnit implements WorkUnit {
		final String className;
		final Object[] constructorArgs;
		
		public ClassWorkUnit(String className, Object... constructorArgs) {
			this.className = className;
			this.constructorArgs = constructorArgs;
		}

		@Override
		public void exec() throws Exception {
			Class<?> task = Thread.currentThread().getContextClassLoader().loadClass(className);						
			Constructor<?> c = task.getConstructors()[0];
			c.setAccessible(true);
			Runnable r = (Runnable) c.newInstance(constructorArgs);
			r.run();
		}
	}

	private static byte[] toBytes(Object x) {
		try {
			ByteArrayOutputStream bos = new ByteArrayOutputStream();
			ObjectOutputStream oos = new ObjectOutputStream(bos);
			oos.writeObject(x);
			oos.close();
			return bos.toByteArray();
		} catch (IOException e) {
			throw new RuntimeException(e);
		}					
	}
	
	@SuppressWarnings("unused")
	private static Object fromBytes(byte[] serialized) {
		return fromBytes(serialized, Thread.currentThread().getContextClassLoader());
	}

	private static Object fromBytes(byte[] serialized, final ClassLoader cl) {
		try {
			ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(serialized)) {
				@Override
				protected Class<?> resolveClass(ObjectStreamClass desc) throws IOException, ClassNotFoundException {
					String name = desc.getName();
					try {
					    return Class.forName(name, false, cl);
					} catch (ClassNotFoundException ex) {
						return super.resolveClass(desc);
					}
				}				
			};
			return ois.readObject();
		} catch (Exception e) {
			throw new RuntimeException(e);
		}		
	}
	
	private class ProxyOut implements InvocationHandler {
		
		private Map<Method, Method> methodMap = new HashMap<Method, Method>();
		private Object target;
		
		public ProxyOut(Object target, Class<?>[] interfaces) {
			this.target = target;
			for(Class<?> i : interfaces) {
				try {
					mapMethods(i);
				} catch (Exception e) {
					throw new RuntimeException(e);
				}
			}
		}

		private void mapMethods(Class<?> i) throws SecurityException, NoSuchMethodException, ClassNotFoundException {
			if (i.getDeclaredMethods() != null) {
				for(Method m : i.getDeclaredMethods()) {
					if (!Modifier.isStatic(m.getModifiers())) {
						Method m2 = target.getClass().getMethod(m.getName(), convertClassesIn(m.getParameterTypes()));
						m2.setAccessible(true);
						methodMap.put(m, m2);
					}
				}
			}
			if (i.getInterfaces() != null) {
				for(Class<?> ii: i.getInterfaces()) {
					mapMethods(ii);
				}
			}
		}

		@SuppressWarnings("rawtypes")
		private Class<?>[] convertClassesIn(Class<?>[] cls) throws ClassNotFoundException {
			Class[] cls2 = new Class[cls.length];
			int n = 0;
			for(Class c: cls) {
				if (c.isPrimitive()) {
					cls2[n++] = c;
				}
				else if (c.isArray()) {
					Class cc = c.getComponentType();
					if (c.getComponentType().isPrimitive()) {
						cls2[n++] = c;
					}
					else {
						Class cc2= convertClassesIn(new Class[]{cc})[0];
						cls2[n++] = Array.newInstance(cc2, 0).getClass();
					}
				}
				else {
					cls2[n++] = cl.loadClass(c.getName());
				}
			}
			return cls2;
		}

		@Override
		public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
			Method m2 = methodMap.get(method);
			Object[] args2 = null;
			if (args != null) {
				args2 = new Object[args.length];
				for(int i = 0; i != args.length; ++i) {
					args2[i] = convertIn(args[i]);
				}
			}
			
			try {
				Object r = m2.invoke(target, args2);
				if (r != null) {
					return convertOut(r);
				}
				else {
					return null;
				}
			}
			catch(InvocationTargetException e) {
				proxyWeaveAndRethrow(proxy, (Throwable)convertOut(e.getCause()));
				return null;
			}
		}
	}
	
	private static class CallableWorkUnit<V> implements WorkUnit {

		final FutureTask<V> future;
		
		public CallableWorkUnit(final Callable<V> x) {			
			future = new FutureTask<V>(new Callable<V>() {
				// Callable wrapper to simplify stack trace weaving
				@Override
				public V call() throws Exception {
					return x.call();
				}
			});
		}

		public CallableWorkUnit(final Runnable x) {			
			future = new FutureTask<V>(new Runnable(){
				// Runnable wrapper to simplify stack trace weaving
				@Override
				public void run() {
					x.run();					
				}
				
			},(V) null);
		}

		@Override
		public void exec() throws Exception {
			future.run();
		}
	}
	
	private class Runner implements Runnable {

		@Override
		public void run() {
			Thread.currentThread().setContextClassLoader(cl);
			ISOLATE.set(Isolate.this);
			
			try{
				while(true) {
					try {
						WorkUnit unit = queue.take();
						if (unit instanceof StopMarker) {
							break;
						}
						else {
							unit.exec();
						}
					}
					catch (ThreadDeath e) {
						return;
					}
					catch (Exception e) {
						System.err.println("Exception in isolate [" + name + "]");
						e.printStackTrace();
					};
				}
			}
			finally{
				queue = null;
			}
		};		
	}
	
	private class IsolatedClassloader extends ClassLoader {
		
		private ClassLoader baseClassloader;
		private String[] packages;
		private Set<String> excludes;
		
		private Collection<String> forbidenPaths = new ArrayList<String>();
		private Collection<URL> externalPaths = new ArrayList<URL>();
		private URLClassLoader cpExtention;
		
		private ProtectionDomain isolateDomain;
		private Map<URL, ProtectionDomain> domainCache = new HashMap<URL, ProtectionDomain>();
		
		IsolatedClassloader(ClassLoader base, String[] packages) {
			super(null);			
			this.baseClassloader = base;
			this.packages = packages;
			this.excludes = new HashSet<String>();

			PermissionCollection pc = new Permissions();
			pc.add(new AllPermission());
			
			ProtectionDomain domain = new ProtectionDomain(
					/* codesource - */ getClass().getProtectionDomain().getCodeSource(),
					/* permissions - */ pc,
					/* classloader - */ null,
					/* principals - */ getClass().getProtectionDomain().getPrincipals());
			this.isolateDomain = domain;
		}
		
		public void exclude(Class<?>... excludedClasses) {
			for (Class<?> clazz : excludedClasses) {
				excludes.add(clazz.getCanonicalName());
			}
		}
		
		public void removeFromClasspath(URL basePath) {
			forbidenPaths.add(basePath.toString());			
		}
		
		public synchronized void addToClasspath(URL path) {
			externalPaths.add(path);
			cpExtention = null;
		}
		
		public void clearAssertionStatus() {
			baseClassloader.clearAssertionStatus();
		}

		public synchronized URL getResource(String name) {
			if (cpExtention == null) {
				cpExtention = new URLClassLoader(externalPaths.toArray(new URL[0]));
			}
			URL r = cpExtention.findResource(name);
			if (r != null) {
				return r;
			}
			r = baseClassloader.getResource(name);
			if (r == null || isForbiden(r)) {
				return null;
			}
			else {
				return r;
			}
		}

		public synchronized Enumeration<URL> getResources(String name) throws IOException {
			if (cpExtention == null) {
				cpExtention = new URLClassLoader(externalPaths.toArray(new URL[0]));
			}
			Vector<URL> result = new Vector<URL>();
			// TODO my have several names
			URL r = cpExtention.findResource(name);
			if (r != null) {
				result.add(r);
			}
			
			Enumeration<URL> en = baseClassloader.getResources(name);
			
			while(en.hasMoreElements()) {
				r = en.nextElement();
				if (!isForbiden(r)) {
					result.add(r);
				}
			}
			
			return result.elements();
		}

		private boolean isForbiden(URL r) {
			if (r != null && !forbidenPaths.isEmpty()) {
				String s = r.toString();
				for(String path: forbidenPaths) {
					if (s.startsWith(path)) {
						return true;
					}
				}
			}
			return false;
		}
		
		public void setClassAssertionStatus(String className, boolean enabled) {
			baseClassloader.setClassAssertionStatus(className, enabled);
		}

		public void setDefaultAssertionStatus(boolean enabled) {
			baseClassloader.setDefaultAssertionStatus(enabled);
		}

		public void setPackageAssertionStatus(String packageName, boolean enabled) {
			baseClassloader.setPackageAssertionStatus(packageName, enabled);
		}

		@Override
		public Class<?> loadClass(String name) throws ClassNotFoundException {
			if (!excludes.contains(name)) {
				for(String prefix: packages) {
					if (name.startsWith(prefix)) {
						return super.loadClass(name, false);
					}
				}
			}
			if (name.equals("sun.awt.AppContext")) {
				new Exception("loading AppContext").printStackTrace();
			}
			Class<?> cc = baseClassloader.loadClass(name);
			return cc;
		}

		@SuppressWarnings("unused")
		private byte[] asBytes(InputStream is) {
			try {
				ByteArrayOutputStream bos = new ByteArrayOutputStream();
				byte[] buf = new byte[4 << 10];
				while(true) {
					int n = is.read(buf);
					if (n < 0) {
						return bos.toByteArray();
					}
					else if (n != 0) {
						bos.write(buf, 0, n);
					}
				}
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
		}

		@Override
		protected Class<?> findClass(String classname) throws ClassNotFoundException {
			try {
				String path = classname.replace('.', '/').concat(".class");
				URL url = getResource(path);
				InputStream res = url.openStream();
				ByteArrayOutputStream bos = new ByteArrayOutputStream();
				byte[] buf = new byte[4096];
				while(true) {
					int x = res.read(buf);
					if (x <= 0) {
						res.close();
						break;
					}
					else {
						bos.write(buf, 0, x);
					}
				}
				byte[] cd = bos.toByteArray();
				try {
					return defineClass(url, classname, cd);
				}
				catch(OutOfMemoryError e) {
					// try it again
					System.gc();
					System.runFinalization();
					System.gc();
					return defineClass(url, classname, cd);
				}
			}
			catch(Exception e) {
				throw new ClassNotFoundException(classname);
			}
		}

		private Class<?> defineClass(URL url, String classname, byte[] cd) throws ClassFormatError {
			Class<?> c = defineClass(classname, cd, 0, cd.length, getProtectionDomain(url));
			return c;
		}

		private synchronized ProtectionDomain getProtectionDomain(URL url) {
			try {
				if ("jar".equals(url.getProtocol())) {
					String jarPath = url.getPath();
					jarPath = jarPath.substring(0, jarPath.lastIndexOf('!'));
					URL jarUrl = new URL(jarPath + "?isolate=" + name);
					ProtectionDomain domain = domainCache.get(jarUrl);
					if (domain == null) {
						domain = new ProtectionDomain(
								new CodeSource(jarUrl, new Certificate[0]), 
								isolateDomain.getPermissions());
						domainCache.put(jarUrl, domain);
					}
					return domain;
				}
				else {
					return isolateDomain;
				}
			} catch (MalformedURLException e) {
				throw new RuntimeException(e);
			}
		}
	}
	
	private static class AnyThrow {

	    public static void throwUncheked(Throwable e) {
	        AnyThrow.<RuntimeException>throwAny(e);
	    }
	   
	    @SuppressWarnings("unchecked")
	    private static <E extends Throwable> void throwAny(Throwable e) throws E {
	        throw (E)e;
	    }
	}
	
	private static class WrapperPrintStream extends FilterOutputStream {

		private String prefix;
		private boolean startOfLine;
		private PrintStream printStream;
		
		public WrapperPrintStream(String prefix, PrintStream printStream) {
			super(printStream);
			this.prefix = prefix;
			this.startOfLine = true;
			this.printStream = printStream;
		}
		
		public void setPrefix(String prefix) {
			this.prefix = prefix;
		}
		
		@Override
		public synchronized void write(int c) throws IOException {
			synchronized(printStream) {
				checkNewLine();
				if (c == '\n') {
					startOfLine = true;
				}
				super.write(c);
				if (startOfLine) {
					// flush after end of line
					super.flush();
				}
			}
		}

		private void checkNewLine() {
			if (startOfLine) {
				printStream.append(prefix);
				startOfLine = false;
			}
		}
	
		@Override
		public void write(byte[] b, int off, int len) throws IOException {
			synchronized(printStream) {
				checkNewLine();
				for (int i = 0; i != len; ++i) {
					if (b[off + i] == '\n') {
						writeByChars(b, off, len);
						return;
					}
				}
				super.write(b, off, len);
			}
		}

		private void writeByChars(byte[] cbuf, int off, int len) throws IOException {
			for (int i = 0; i != len; ++i) {
				write(cbuf[off + i]);
			}
		}

		@Override
		public void close() throws IOException {
			super.flush();
		}
	}
	
	private static abstract class PrintStreamMultiplexor extends PrintStream {
		
		protected abstract PrintStream resolve();
		
		public PrintStreamMultiplexor() {
			super(new ByteArrayOutputStream(8));
		}
		
		public int hashCode() {
			return resolve().hashCode();
		}
		public void write(byte[] b) throws IOException {
			resolve().write(b);
		}
		public boolean equals(Object obj) {
			return resolve().equals(obj);
		}
		public String toString() {
			return resolve().toString();
		}
		public void flush() {
			resolve().flush();
		}
		public void close() {
			resolve().close();
		}
		public boolean checkError() {
			return resolve().checkError();
		}
		public void write(int b) {
			resolve().write(b);
		}
		public void write(byte[] buf, int off, int len) {
			resolve().write(buf, off, len);
		}
		public void print(boolean b) {
			resolve().print(b);
		}
		public void print(char c) {
			resolve().print(c);
		}
		public void print(int i) {
			resolve().print(i);
		}
		public void print(long l) {
			resolve().print(l);
		}
		public void print(float f) {
			resolve().print(f);
		}
		public void print(double d) {
			resolve().print(d);
		}
		public void print(char[] s) {
			resolve().print(s);
		}
		public void print(String s) {
			resolve().print(s);
		}
		public void print(Object obj) {
			resolve().print(obj);
		}
		public void println() {
			resolve().println();
		}
		public void println(boolean x) {
			resolve().println(x);
		}
		public void println(char x) {
			resolve().println(x);
		}
		public void println(int x) {
			resolve().println(x);
		}
		public void println(long x) {
			resolve().println(x);
		}
		public void println(float x) {
			resolve().println(x);
		}
		public void println(double x) {
			resolve().println(x);
		}
		public void println(char[] x) {
			resolve().println(x);
		}
		public void println(String x) {
			resolve().println(x);
		}
		public void println(Object x) {
			resolve().println(x);
		}
		public PrintStream printf(String format, Object... args) {
			return resolve().printf(format, args);
		}
		public PrintStream printf(Locale l, String format, Object... args) {
			return resolve().printf(l, format, args);
		}
		public PrintStream format(String format, Object... args) {
			return resolve().format(format, args);
		}
		public PrintStream format(Locale l, String format, Object... args) {
			return resolve().format(l, format, args);
		}
		public PrintStream append(CharSequence csq) {
			return resolve().append(csq);
		}
		public PrintStream append(CharSequence csq, int start, int end) {
			return resolve().append(csq, start, end);
		}
		public PrintStream append(char c) {
			return resolve().append(c);
		}
	}
	
	@SuppressWarnings("serial")
	private static abstract class PropertiesMultiplexor extends Properties {
		
		protected abstract Properties resolve();

		public Object setProperty(String key, String value) {
			return resolve().setProperty(key, value);
		}

		public void load(Reader reader) throws IOException {
			resolve().load(reader);
		}

		public int size() {
			return resolve().size();
		}

		public boolean isEmpty() {
			return resolve().isEmpty();
		}

		public Enumeration<Object> keys() {
			return resolve().keys();
		}

		public Enumeration<Object> elements() {
			return resolve().elements();
		}

		public boolean contains(Object value) {
			return resolve().contains(value);
		}

		public boolean containsValue(Object value) {
			return resolve().containsValue(value);
		}

		public boolean containsKey(Object key) {
			return resolve().containsKey(key);
		}

		public Object get(Object key) {
			return resolve().get(key);
		}

		public void load(InputStream inStream) throws IOException {
			resolve().load(inStream);
		}

		public Object put(Object key, Object value) {
			return resolve().put(key, value);
		}

		public Object remove(Object key) {
			return resolve().remove(key);
		}

		public void putAll(Map<? extends Object, ? extends Object> t) {
			resolve().putAll(t);
		}

		public void clear() {
			resolve().clear();
		}

		public Object clone() {
			return resolve().clone();
		}

		public String toString() {
			return resolve().toString();
		}

		public Set<Object> keySet() {
			return resolve().keySet();
		}

		public Set<Entry<Object, Object>> entrySet() {
			return resolve().entrySet();
		}

		public Collection<Object> values() {
			return resolve().values();
		}

		public boolean equals(Object o) {
			return resolve().equals(o);
		}

		@SuppressWarnings("deprecation")
		public void save(OutputStream out, String comments) {
			resolve().save(out, comments);
		}

		public int hashCode() {
			return resolve().hashCode();
		}

		public void store(Writer writer, String comments) throws IOException {
			resolve().store(writer, comments);
		}

		public void store(OutputStream out, String comments) throws IOException {
			resolve().store(out, comments);
		}

		public void loadFromXML(InputStream in) throws IOException,
				InvalidPropertiesFormatException {
			resolve().loadFromXML(in);
		}

		public void storeToXML(OutputStream os, String comment)
				throws IOException {
			resolve().storeToXML(os, comment);
		}

		public void storeToXML(OutputStream os, String comment, String encoding)
				throws IOException {
			resolve().storeToXML(os, comment, encoding);
		}

		public String getProperty(String key) {
			return resolve().getProperty(key);
		}

		public String getProperty(String key, String defaultValue) {
			return resolve().getProperty(key, defaultValue);
		}

		public Enumeration<?> propertyNames() {
			return resolve().propertyNames();
		}

		public Set<String> stringPropertyNames() {
			return resolve().stringPropertyNames();
		}

		public void list(PrintStream out) {
			resolve().list(out);
		}

		public void list(PrintWriter out) {
			resolve().list(out);
		}
	}
}