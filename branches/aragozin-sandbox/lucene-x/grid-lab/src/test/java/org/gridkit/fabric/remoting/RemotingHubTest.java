package org.gridkit.fabric.remoting;

import java.io.IOException;
import java.io.Serializable;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.rmi.Remote;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.gridkit.fabric.remoting.RmiChannelPipeTest.ProxyCallable;
import org.gridkit.fabric.remoting.RmiChannelPipeTest.SelfIdentity;
import org.gridkit.fabric.remoting.hub.Ping;
import org.gridkit.fabric.remoting.hub.RemotingEndPoint;
import org.gridkit.fabric.remoting.hub.RemotingHub;
import org.gridkit.fabric.remoting.hub.RemotingHub.SessionEventListener;
import org.gridkit.fabric.remoting.hub.SimpleSocketAcceptor;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class RemotingHubTest {

	private RemotingHub hub;
	private RemotingEndPoint endPoint1;
	private RemotingEndPoint endPoint2;
	private SimpleSocketAcceptor acceptor;
	private ExecutorService remoteExecutor1;
	private ExecutorService remoteExecutor2;
	
	@Before
	public void initHub() throws InterruptedException, BrokenBarrierException, TimeoutException {
		hub = new RemotingHub();
		
		final CountDownLatch latch = new CountDownLatch(2);
		
		SessionEventListener sessionListener = new SessionEventListener() {
			@Override
			public void reconnected(DuplexStream stream) {
				System.out.println("HUB: reconnected: " + stream);
			}
			
			@Override
			public void interrupted(DuplexStream stream) {
				System.out.println("HUB: interrupted: " + stream);
			}
			
			@Override
			public void connected(DuplexStream stream) {
				System.out.println("HUB: connected: " + stream);
				latch.countDown();
			}
			
			@Override
			public void closed() {
				System.out.println("HUB: closed");
			}
		};
		
		String uid1 = hub.newSession(sessionListener);
		String uid2 = hub.newSession(sessionListener);
		
		
		acceptor = new SimpleSocketAcceptor();
		ServerSocket ssock;
		try {
			ssock = new ServerSocket(21000);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
		
		acceptor.bind(ssock, hub);
		acceptor.start();
		
		endPoint1 = new RemotingEndPoint(uid1, new InetSocketAddress("localhost", 21000));
		new Thread(endPoint1).start();
		
		remoteExecutor1 = hub.getExecutionService(uid1);

		endPoint2 = new RemotingEndPoint(uid2, new InetSocketAddress("localhost", 21000));
		new Thread(endPoint2).start();
		
		remoteExecutor2 = hub.getExecutionService(uid2);
		
		latch.await(5000, TimeUnit.MILLISECONDS);
	}
	
	@After
	public void shutdown() {
		acceptor.close();
	}
	
	@Test
	public void verify_executor1() throws InterruptedException, ExecutionException {
		Assert.assertEquals("abc", remoteExecutor1.submit(new Echo("abc")).get());
	}

	@Test
	public void verify_executor2() throws InterruptedException, ExecutionException {
		Assert.assertEquals("abc", remoteExecutor2.submit(new Echo("abc")).get());
	}
	
	@Test
	public void transitive_proxy_test() throws InterruptedException, ExecutionException {
		Future<Callable<String>> future = remoteExecutor1.submit(new RemoteProxyMaker<String>(new Echo<String>("123")));
		String result = remoteExecutor2.submit(future.get()).get();
		Assert.assertEquals("123", result);		
	}
	
	public static class Echo<V> implements Callable<V>, Serializable {

		private V sound;
		
		public Echo(V sound) {
			this.sound = sound;
		}

		@Override
		public V call() throws Exception {
			return sound;
		}
	}

	public static class SelfIdentity implements Callable<SelfIdentity>, Serializable {
		
		public SelfIdentity() {
		}
		
		@Override
		public SelfIdentity call() throws Exception {
			return this;
		}
	}
	
	public static class NotSerializable implements Callable<String> {
		@Override
		public String call() throws Exception {
			return "NotSerializable";
		}
	}

	public static class SerializableAdapter<V> implements Callable<V>, Serializable {
		
		private Callable<V> callable;
		
		public SerializableAdapter(Callable<V> callable) {
			this.callable = callable;
		}

		@Override
		public V call() throws Exception {
			return callable.call();
		}
	}
	
	public static interface ProxyCallable<V> extends Callable<V>, Remote {
		
	}
	
	public static class ProxyAdapter<V> implements ProxyCallable<V> {
		
		private Callable<V> callable;
		
		public ProxyAdapter(Callable<V> callable) {
			this.callable = callable;
		}

		@Override
		public V call() throws Exception {
			return callable.call();
		}
	}	
	
	public static class RemoteProxyMaker<V> implements Callable<Callable<V>>, Serializable {
		
		private Callable<V> nested;

		public RemoteProxyMaker(Callable<V> nested) {
			this.nested = nested;
		}

		@Override
		public Callable<V> call() throws Exception {
			return new ProxyAdapter<V>(nested);
		}
	}
}