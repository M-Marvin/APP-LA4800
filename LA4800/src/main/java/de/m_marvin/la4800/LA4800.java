package de.m_marvin.la4800;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import de.m_marvin.serialportaccess.SerialPort;
import de.m_marvin.simplelogging.Log;
import de.m_marvin.simplelogging.api.Logger;
import de.m_marvin.simplelogging.impl.SynchronizedLogger;

public class LA4800 {
	
	protected static final char DELIMITER = ',';
	protected static final char TERMINATOR = '\r';
	protected static final String IDENTIFICATION_COMMAND = "IDENT";
	protected static final Pattern IDENTIFIER = Pattern.compile("(?<model>LA(?:4800|3200))\s+V(?<version>[\\d\\.]+)");
	protected static final Pattern MSG_OK = Pattern.compile("OK");
	protected static final Pattern MSG_ERROR = Pattern.compile("ERROR (\\d\\d)");
	
	protected final Logger logger;
	protected final SerialPort port;
	protected long timeout = 5;
	protected TimeUnit timeoutUnit = TimeUnit.SECONDS;
	protected ThreadRX rxThread;
	protected ThreadTX txThread;
	protected record PendingCmd(String cmd, CompletableFuture<String[]> future) {}
	protected Queue<PendingCmd> pending = new ArrayDeque<>();
	protected Queue<String> received = new ArrayDeque<>();
	protected String model;
	protected String version;

	public LA4800(String portName) {
		this(portName, Log.defaultLogger());
	}
	
	public LA4800(String portName, Logger logger) {
		this.port = new SerialPort(portName);
		this.logger = new SynchronizedLogger(logger);
	}
	
	public void setComTimeout(long time, TimeUnit unit) {
		this.timeout = time;
		this.timeoutUnit = unit;
	}
	
	public boolean isConnected() {
		return this.port.isOpen();
	}

	public void connect() throws IOException {
		if (!this.port.openPort()) throw new IOException("could not connect to serial port: " + this.port);
		this.port.setTimeouts(1, 1000);
		this.port.setBaud(9600);
		this.logger.info("try connected to logic analyzer ...");
		
		assert !(this.rxThread != null && this.rxThread.isAlive()) : "RX thread already running!";
		assert !(this.txThread != null && this.txThread.isAlive()) : "TX thread already running!";
		this.rxThread = new ThreadRX();
		this.txThread = new ThreadTX();
		this.rxThread.start();
		this.txThread.start();
		
		try {
			String[] response = command(IDENTIFICATION_COMMAND).join();
			if (response.length != 1) {
				disconnect();
				throw new IOException("indentification failed, no response!");
			}
			Matcher m = IDENTIFIER.matcher(response[0]);
			
			if (!m.find()) {
				disconnect();
				throw new IOException("indentification failed, invalid response: " + response[0]);
			} else {
				this.model = m.group("model");
				this.version = m.group("version");
				this.logger.info("connected to logic analyzer %s version %s", this.model, this.version);
			}
		} catch (CompletionException e) {
			disconnect();
			throw new IOException("exception thrown when requesting identification!", e);
		} catch (IOException e) {
			throw new IOException("failed to perform identification request, exception occured!", e);
		}
	}
	
	public void disconnect() {
		if (!isConnected()) return;
		this.port.closePort();
		try {
			this.rxThread.interrupt();
			this.rxThread.join(this.timeoutUnit.toMillis(timeout));
			this.txThread.interrupt();
			this.txThread.join(this.timeoutUnit.toMillis(timeout));
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		this.pending.clear();
		this.logger.info("disconnected from device");
	}
	
	public String getModel() {
		return model;
	}
	
	public String getVersion() {
		return version;
	}
	
	public CompletableFuture<String[]> command(String command) throws IOException {
		return command(new String[] {command})[0];
	}
	
	public boolean isBusy() {
		// All features jave timedout or completed, ignore them
		if (this.pending.stream().filter(p -> p.future().isDone()).count() == this.pending.size())
			this.pending.clear();
		return !this.pending.isEmpty() && isConnected();
	}
	
	public void awaitReady() throws InterruptedException {
		synchronized (this) {
			while (isBusy()) this.wait();
		}
	}
	
	public CompletableFuture<String[]>[] command(String... commands) throws IOException {

		if (!isConnected()) throw new IOException("not connected to device!");
		if (isBusy()) throw new IOException("connection is busy!");
		
		@SuppressWarnings("unchecked")
		CompletableFuture<String[]>[] futures = (CompletableFuture<String[]>[]) new CompletableFuture[commands.length];
		
		for (int i = 0; i < commands.length; i++) {
			CompletableFuture<String[]> future = new CompletableFuture<String[]>().orTimeout(this.timeout, this.timeoutUnit);
			this.pending.add(new PendingCmd(commands[i], future));
			futures[i] = future;
		}
		
		deliverCommand();
		return futures;
	}
	
	protected void deliverCommand() {
		if (this.pending.isEmpty()) return;
		
		String cmd = this.pending.peek().cmd();
		this.txThread.send(cmd + TERMINATOR);
	}
	
	protected void deliverResponse(String[] response) {
		this.received.addAll(Arrays.asList(response));
		
		List<String> args = new ArrayList<>();
		while (!this.received.isEmpty()) {
			if (this.pending.isEmpty()) {
				String remaining = this.received.stream().reduce((a, b) -> a + "," + b).get();
				this.logger.warn("eccess data received: %s", remaining);
				this.received.clear();
				break;
			}
			String arg = this.received.poll();
			if (MSG_OK.matcher(arg).find()) {
				this.pending.poll().future().complete(args.toArray(String[]::new));
				deliverCommand();
				args.clear();
			} else {
				Matcher m = MSG_ERROR.matcher(arg);
				if (m.find()) {
					int cmdl = Integer.parseInt(m.group(1));
					this.logger.warn("command failed: %s (%d)", this.pending.peek().cmd(), cmdl);
					this.pending.poll().future().completeExceptionally(new Exception("command failed on device: " + cmdl));
					deliverCommand();
					args.clear();
				}
				args.add(arg);
			}
		}
		if (!args.isEmpty()) this.received.addAll(args);
	}
	
	protected class ThreadRX extends Thread {
		
		protected final BufferedReader in;
		
		public ThreadRX() {
			super("LA4800 Com RX");
			setDaemon(true);
			this.in = new BufferedReader(new InputStreamReader(LA4800.this.port.getInputStream()));
		}
		
		@Override
		public void run() {
			while (LA4800.this.port.isOpen()) {
				try {
					StringBuffer arg = new StringBuffer(256);
					List<String> arguments = new ArrayList<>();
					while (isBusy()) {
						char c = (char) this.in.read();
						if (c == DELIMITER || c == TERMINATOR) {
							arguments.add(arg.toString());
							arg = new StringBuffer();
							if (c == TERMINATOR) {
								deliverResponse(arguments.toArray(String[]::new));
								arguments.clear();
							}
						} else {
							arg.append(c);
						}
					}
				} catch (IOException e) {
					LA4800.this.logger.warn("failed to receive response(s)", e);
					LA4800.this.pending.forEach(p -> p.future().completeExceptionally(new IOException("failed to receive response!", e)));
					LA4800.this.pending.clear();
				}
			}
		}
		
	}
	
	protected class ThreadTX extends Thread {
		
		protected final OutputStream out;
		protected String toSend;
		
		public ThreadTX() {
			super("LA4800 Com TX");
			setDaemon(true);
			this.out = LA4800.this.port.getOutputStream(256);
		}
		
		@Override
		public void run() {
			while (LA4800.this.port.isOpen()) {
				if (this.toSend != null) {
					try {
						this.out.write(this.toSend.getBytes(StandardCharsets.US_ASCII));
						this.out.flush();
					} catch (IOException e) {
						LA4800.this.logger.warn("failed to send command(s): %s", this.toSend, e);
						LA4800.this.pending.forEach(p -> p.future().completeExceptionally(new IOException("failed to send command!", e)));
						LA4800.this.pending.clear();
					}
					this.toSend = null;
				}
				// Wait until notified/interrupted
				try { Thread.currentThread().join(); } catch (InterruptedException e) {}
			}
		}
		
		public void send(String message) {
			this.toSend = message;
			this.interrupt();
		}
		
	}
	
}
