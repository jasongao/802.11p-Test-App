package edu.mit.csail.jasongao.sonar;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;

import android.os.Handler;
import android.util.Log;

public class NetworkThread extends Thread {
	private static final String TAG = "NetworkThread";

	private Handler muxHandler;

	// UDP over IPv4 networking
	private static final int MAX_PACKET_SIZE = 16384; // bytes
	private static final String IFACE = "eth0";
	private static final String BCAST_ADDR = "192.168.42.255";
	private static final int PORT = 4200;
	private DatagramSocket mySocket;
	private boolean socketOK = true;
	private InetAddress myBcastIPAddress;
	private InetAddress myIPAddress;

	/** NetworkThread constructor */
	public NetworkThread(Handler h) {
		muxHandler = h;

		// Determine local IP address
		myIPAddress = null;
		try {
			NetworkInterface intf = NetworkInterface.getByName(IFACE);
			for (Enumeration<InetAddress> enumIpAddr = intf.getInetAddresses(); enumIpAddr
					.hasMoreElements();) {
				InetAddress inetAddress = enumIpAddr.nextElement();
				if (!inetAddress.isLoopbackAddress()) {
					myIPAddress = inetAddress;
				}
			}
			if (myIPAddress == null)
				throw new Exception("no addresses bound to eth0");
		} catch (Exception e) {
			Log.e(TAG, "can't determine local IP address: " + e.toString());
			return;
		}

		// Determine broadcast IP address
		try {
			myBcastIPAddress = getBroadcastAddress();
		} catch (Exception e) {
			Log.e(TAG, "Cannot get my broadcast IP address" + e.toString());
			return;
		}

		openSocket();

		Log.i(TAG, "started, local IP address:" + myIPAddress);
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
			mySocket = new DatagramSocket(PORT);
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
		byte[] receiveData = new byte[MAX_PACKET_SIZE];

		while (socketOK) {
			DatagramPacket dPacket = new DatagramPacket(receiveData,
					receiveData.length);
			try {
				mySocket.receive(dPacket);
			} catch (IOException e) {
				Log.e(TAG, "Exception on mySocket.receive: " + e.getMessage());
				socketOK = false;
				continue;
			}

			// filter out our own UDP broadcasts
			if (dPacket.getAddress().equals(myIPAddress))
				continue;

			// Return received data to caller's handler
			// Log.i(TAG, "Received UDP payload: " + dPacket.getLength());
			muxHandler.obtainMessage(SonarActivity.PACKET_RECV,
					dPacket.getData()).sendToTarget();
			//muxHandler.obtainMessage(SonarActivity.PACKET_RECV).sendToTarget();
		} // end while(socketOK)

		Log.i(TAG, "NetworkThread exiting.");
	} // end run()

	/** Send an UDP packet to the broadcast address */
	public synchronized void sendData(byte[] sendData) throws IOException {
		mySocket.send(new DatagramPacket(sendData, sendData.length,
				myBcastIPAddress, PORT));
	}

	/** Calculate the broadcast IP we need to send the packet along. */
	public synchronized InetAddress getBroadcastAddress() throws IOException {
		//return InetAddress.getByName("192.168.5.255");
		return InetAddress.getByName(BCAST_ADDR);
	}

	/** Return our stored local IP address. */
	public synchronized InetAddress getLocalAddress() {
		return myIPAddress;
	}
}