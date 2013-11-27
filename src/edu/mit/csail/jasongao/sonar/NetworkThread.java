package edu.mit.csail.jasongao.sonar;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;

import android.os.Handler;
import android.util.Log;

public class NetworkThread extends Thread {
	private static final String TAG = "NetworkThread";

	private Handler muxHandler;

	// UDP over IPv4 networking
	private static final int MAX_PACKET_SIZE = 1400;
	public static final String IFACE = "eth0";
	private DatagramSocket mySocket;
	private boolean socketOK = true;
	public InetAddress localAddress;
	public InetAddress broadcastAddress;
	private static final int DEST_PORT = 32768;
	private static byte[] receiveDataBuffer = null;

	private static final String LOCAL_ADDR = "192.168.42.2";

	/** NetworkThread constructor */
	public NetworkThread(Handler h) {
		muxHandler = h;

		// bring up interface
		SystemHelper.execSUCommand("ifconfig " + IFACE + " " + LOCAL_ADDR
				+ " netmask 255.255.255.0 up", false);

		System.setProperty("java.net.preferIPv4Stack", "true");

		// Determine local IP address
		localAddress = null;
		try {
			NetworkInterface intf = NetworkInterface.getByName(IFACE);
			for (Enumeration<InetAddress> enumIpAddr = intf.getInetAddresses(); enumIpAddr
					.hasMoreElements();) {
				InetAddress inetAddress = enumIpAddr.nextElement();
				if (!inetAddress.isLoopbackAddress()) {
					localAddress = inetAddress;
				}
			}
			if (localAddress == null) {
				throw new Exception("no addresses bound to " + IFACE);
			}
		} catch (Exception e) {
			Log.e(TAG, "can't determine local IP address: " + e.toString());
			return;
		}

		// Determine broadcast IP address
		try {
			NetworkInterface nif = NetworkInterface
					.getByInetAddress(localAddress);
			for (InterfaceAddress addr : nif.getInterfaceAddresses()) {
				broadcastAddress = addr.getBroadcast();
			}
			if (broadcastAddress == null) {
				throw new Exception("no broadcast address bound to " + IFACE);
			}
		} catch (Exception e) {
			Log.e(TAG, "Cannot determine broadcast IP address: " + e.toString());
			return;
		}

		openSocket();

		Log.i(TAG, "started, local: " + localAddress + ", broadcast: "
				+ broadcastAddress);
	}

	/** Close the socket before exiting the application */
	public synchronized void closeSocket() {
		if (mySocket != null && !mySocket.isClosed())
			mySocket.close();
	}

	/** Create a socket if necessary and set it up for receiving broadcasts. */
	private void openSocket() {
		if (mySocket != null && !mySocket.isClosed())
			mySocket.close();

		try {
			mySocket = new DatagramSocket(DEST_PORT);
			mySocket.setBroadcast(true);

			Log.i(TAG, String.format(
					"Initial socket buffers: %d receive, %d send",
					mySocket.getReceiveBufferSize(),
					mySocket.getSendBufferSize()));

			mySocket.setReceiveBufferSize(MAX_PACKET_SIZE);
			mySocket.setSendBufferSize(MAX_PACKET_SIZE);

			Log.i(TAG, String.format("Set socket buffers: %d receive, %d send",
					mySocket.getReceiveBufferSize(),
					mySocket.getSendBufferSize()));
		} catch (Exception e) {
			Log.e(TAG, "Cannot open socket: " + e.getMessage());
			socketOK = false;
			return;
		}
	}

	/** If not socketOK, then receive loop thread will stop */
	public synchronized boolean socketIsOK() {
		return socketOK;
	}

	/** Thread's receive loop for UDP packets */
	@Override
	public void run() {
		receiveDataBuffer = new byte[MAX_PACKET_SIZE];
		DatagramPacket dPacket = new DatagramPacket(receiveDataBuffer,
				receiveDataBuffer.length);
		while (socketOK) {
			try {
				dPacket.setData(receiveDataBuffer);
				mySocket.receive(dPacket);
			} catch (IOException e) {
				Log.e(TAG, "Exception on mySocket.receive: " + e.getMessage());
				socketOK = false;
				continue;
			}

			// filter out our own UDP broadcasts
			if (dPacket.getAddress().equals(localAddress))
				continue;

			// Return received data to caller's handler
			// Log.i(TAG, "Received UDP payload: " + dPacket.getLength());
			muxHandler.obtainMessage(SonarActivity.PACKET_RECV,
					dPacket.getData()).sendToTarget();
		} // end while(socketOK)

		Log.i(TAG, "NetworkThread exiting.");
	} // end run()

	/** Send an UDP packet to the broadcast address */
	public synchronized void broadcast(byte[] sendData) throws IOException {
		send(sendData, broadcastAddress);
	}

	/** Send an UDP packet to an address */
	public synchronized void send(byte[] sendData, InetAddress dst)
			throws IOException {
		mySocket.send(new DatagramPacket(sendData, sendData.length, dst,
				DEST_PORT));
	}

	/** Send an UDP packet to a hostname or IP address provided as a String */
	public synchronized void send(byte[] sendData, String host)
			throws IOException {
		send(sendData, InetAddress.getByName(host));
	}
}