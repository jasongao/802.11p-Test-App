package edu.mit.csail.jasongao.sonar;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.nio.ByteBuffer;

import android.app.Activity;
import android.content.Context;
import android.location.LocationManager;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiManager.MulticastLock;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Message;
import android.os.PowerManager;
import android.os.StrictMode;
import android.util.Log;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ListView;

public class SonarActivity extends Activity {
	final static private String TAG = "SonarActivity";

	private static final String BINPATH = "/data/local/bin/";
	public static final String TCPDUMP = "tcpdump";
	private static CaptureThread tcpdumpCmd = null;

	// Attributes
	private boolean currentlyRepeating = false;
	private long packetsReceived = 0;
	private long packetsSent = 0;
	private boolean sendConfigPackets = false;
	private boolean sendGetFifoCountPacket = false;

	// UI
	ArrayAdapter<String> receivedMessages, receivedMessages2;

	// Logging to file
	File logFile;
	PrintWriter logWriter;

	// Network
	private NetworkThread netThread;

	// Android components
	PowerManager.WakeLock wakeLock = null;
	LocationManager locManager;
	MulticastLock mcLock = null;

	// Handler message types
	protected final static int LOG = 3;
	protected final static int PACKET_RECV = 4;
	protected final static int CAPTURE_RECV = 5;

	/** Handle messages from various components */
	private final Handler myHandler = new Handler() {
		@Override
		public void handleMessage(Message msg) {
			switch (msg.what) {
			case PACKET_RECV:
				byte[] pingBytes = (byte[]) msg.obj;
				logMsg(receivedMessages2, String.format(
						"Received packet %d of size %d, data: %s",
						packetsReceived, pingBytes.length,
						bytesToHex(pingBytes)));
				packetsReceived++;
				break;
			case CAPTURE_RECV:
				String capLine = (String) msg.obj;
				logMsg(receivedMessages2, capLine);
				break;
			case LOG: // Write a string to log file and UI log display
				logMsg(receivedMessages, (String) msg.obj);
				break;
			}
		}
	};

	private final boolean LOGCAT_WRITE = false;
	private final boolean TEXTLOG_WRITE = false;
	private final boolean LISTVIEW_LIMIT = true;
	private final long LISTVIEW_LIMIT_COUNT = 200;

	/** Log message and also display on screen */
	public void logMsg(ArrayAdapter<String> adapter, String line) {
		line = String.format("%d: %s", System.currentTimeMillis(), line);

		// limit maximum size of adapter
		if (LISTVIEW_LIMIT && adapter.getCount() > LISTVIEW_LIMIT_COUNT) {
			adapter.remove(adapter.getItem(0));
		}

		adapter.add((String) line);

		if (LOGCAT_WRITE) {
			Log.i(TAG, line);
		}

		if (TEXTLOG_WRITE && logWriter != null) {
			logWriter.println((String) line);
		}
	}

	/** Periodically repeating packet send */
	private Runnable repeatingPacketR = new Runnable() {
		public void run() {
			myHandler.postDelayed(this, packetDelay);
			for (int i = 0; i < 10; i++) {
				sendData();
			}
		}
	};
	
	int packetDelay = 1;

	private void repeatingPacketStart() {
		EditText editTextPacketDelay = (EditText) findViewById(R.id.editTextPacketDelay);
		CheckBox checkBoxUsePacketDelay = (CheckBox) findViewById(R.id.checkBoxUsePacketDelay);
		
		if (checkBoxUsePacketDelay.isChecked()) {
			packetDelay = parseAndLimitRange(editTextPacketDelay.getText().toString(), 1, 10000);
			editTextPacketDelay.setText(Integer.toString(packetDelay));
		} else {
			packetDelay = 1;
		}
		
		myHandler.post(repeatingPacketR);
	}

	private void repeatingPacketStop() {
		myHandler.removeCallbacks(repeatingPacketR);
	}

	final protected static char[] hexArray = { '0', '1', '2', '3', '4', '5',
			'6', '7', '8', '9', 'A', 'B', 'C', 'D', 'E', 'F' };

	public static String bytesToHex(byte[] bytes) {
		char[] hexChars = new char[bytes.length * 2];
		int v;
		for (int j = 0; j < bytes.length; j++) {
			v = bytes[j] & 0xFF;
			hexChars[j * 2] = hexArray[v >>> 4];
			hexChars[j * 2 + 1] = hexArray[v & 0x0F];
		}
		return new String(hexChars);
	}

	private int parseAndLimitRange(String s, int min, int max) {
		int value = Integer.parseInt(s);
		value = Math.min(max, Math.max(min, value));
		return value;
	}

	/** Send test data */
	private void sendData() {
		// Get rate and length from GUI
		// TODO should eventually move this to onCreate, but while prototyping
		// keep it here just for ease of reading
		EditText editTextRate = (EditText) findViewById(R.id.editTextRate);
		EditText editTextLength = (EditText) findViewById(R.id.editTextLength);
		EditText editTextAndroidGain = (EditText) findViewById(R.id.editTextAndroidGain);
		EditText editTextDataPackets = (EditText) findViewById(R.id.editTextDataPackets);
		CheckBox checkBoxUseAndroidGain = (CheckBox) findViewById(R.id.checkBoxUseAndroidGain);
		CheckBox checkBoxSetEnable = (CheckBox) findViewById(R.id.checkBoxSetEnable);

		// Parse and validate values from GUI
		int rate = 1;
		int length = 64;
		int dataPackets = 16;

		boolean useAndroidGain = true;
		boolean setEnable = true;
		int androidGain = 4;
		int androidGainEn = 1;

		try {
			useAndroidGain = checkBoxUseAndroidGain.isChecked();
			setEnable = checkBoxSetEnable.isChecked();

			dataPackets = Integer.parseInt(editTextDataPackets.getText()
					.toString());
			// Restrict length to be between 1 to 1024
			dataPackets = Math.min(1024, Math.max(1, dataPackets));
			editTextDataPackets.setText(Integer.toString(dataPackets));

			length = Integer.parseInt(editTextLength.getText().toString());
			// Restrict length to be between 4 to 4096
			length = Math.min(4096, Math.max(4, length));
			// Restrict length to be a multiple of 4
			// length -= (length % 4);
			editTextLength.setText(Integer.toString(length));

			rate = Integer.parseInt(editTextRate.getText().toString());
			// Restrict rate to be 1 to 7
			rate = Math.min(7, Math.max(1, rate));
			editTextRate.setText(Integer.toString(rate));

			androidGain = Integer.parseInt(editTextAndroidGain.getText()
					.toString());
			// Restrict androidGain to be 0 to 63 (6-bit value)
			androidGain = Math.min(63, Math.max(0, androidGain));
			editTextAndroidGain.setText(Integer.toString(androidGain));
		} catch (NumberFormatException e1) {
			logMsg(receivedMessages,
					"INVALID NUMBER FOR LENGTH, RATE, GAIN, OR DELAY!");
			return;
		}

		// Construct UDP packet containing multiple RRR packets
		ByteArrayOutputStream bos = new ByteArrayOutputStream();
		try {
			if (sendGetFifoCountPacket) {
				// Send several, FPGA Ethernet stack has a min size limitation
				for (int i = 0; i < 7; i++) {
					// Add RRR packet for PacketGen_GetCount
					// (4-byte header 080a8001 and any 4-byte value)
					bos.write(new byte[] { (byte) 0x08, (byte) 0x0a,
							(byte) 0x80, (byte) 0x01, (byte) 0x00, (byte) 0x00,
							(byte) 0x00, (byte) 0x00 });
				}
				sendGetFifoCountPacket = false;
			} else if (sendConfigPackets) {
				// Add RRR packet for PacketGen_SetEnable
				// (4-byte header 08090001 and 1-bit value)
				byte[] setEnable_pkt = new byte[] { (byte) 0x08, (byte) 0x09,
						(byte) 0x00, (byte) 0x01, (byte) 0x00, (byte) 0x00,
						(byte) 0x00, (byte) (setEnable ? 0x01 : 0x00) };
				bos.write(setEnable_pkt);

				// Add RRR packet for PacketGen_SetRate
				// (4-byte header 08080001 and 4-byte value)
				byte[] rate_pkt = new byte[] { (byte) 0x08, (byte) 0x08,
						(byte) 0x00, (byte) 0x01, (byte) 0x00, (byte) 0x00,
						(byte) 0x00, (byte) rate };
				bos.write(rate_pkt);

				androidGainEn = useAndroidGain ? 1 : 0;

				// Add RRR packet to set gaincontrol_AndroidGainEn
				// (4-byte header 08108001 and 1-bit value)
				byte[] androidGainEn_pkt = new byte[] { (byte) 0x08,
						(byte) 0x10, (byte) 0x80, (byte) 0x01, (byte) 0x00,
						(byte) 0x00, (byte) 0x00, (byte) androidGainEn };
				bos.write(androidGainEn_pkt);

				if (useAndroidGain) {
					// Add RRR packet to set gaincontrol_AndroidGain
					// (4-byte header 08100001 and 6-bit value)
					byte[] androidGain_pkt = new byte[] { (byte) 0x08,
							(byte) 0x10, (byte) 0x00, (byte) 0x01, (byte) 0x00,
							(byte) 0x00, (byte) 0x00, (byte) androidGain };
					bos.write(androidGain_pkt);
				}

				sendConfigPackets = false;
			} else {
				// Add RRR packet for PacketGen_SetLength
				// (4-byte header 08088001 and 4-byte value)
				ByteBuffer b = ByteBuffer.allocate(4);
				b.putInt(length);
				byte[] length_bytes = b.array();
				byte[] length_pkt = new byte[] { (byte) 0x08, (byte) 0x08,
						(byte) 0x80, (byte) 0x01, (byte) 0x00, (byte) 0x00,
						length_bytes[2], length_bytes[3] };
				bos.write(length_pkt);

				// Add RRR data packet(s) for PacketGen_SendPacket
				// (4-byte header 080a0001 and 4-byte value)
				for (int i = 0; i < length / 4; i++) {
					byte[] data_pkt = new byte[] { (byte) 0x08, (byte) 0x0A,
							(byte) 0x00, (byte) 0x01, (byte) 0x01, (byte) 0x23,
							(byte) 0x45, (byte) 0x67, };
					bos.write(data_pkt);
				}

				// Add RRR packet for PacketGen_GetCount
				// (4-byte header 080a8001 and any 4-byte value)
				bos.write(new byte[] { (byte) 0x08, (byte) 0x0a, (byte) 0x80,
				(byte) 0x01, (byte) 0x00, (byte) 0x00, (byte) 0x00,
				(byte) 0x00 });
			}
		} catch (IOException e1) {
			logMsg(receivedMessages, "Error creating RRR packet.");
			e1.printStackTrace();
		}

		sendPacket(bos.toByteArray());
	}

	/** Send a UDP packet that the FPGA can understand */
	private boolean sendPacket(byte[] packet) {
		// Add 2-byte zero pad to reach a multiple of 4 bytes (b/c eth 14 bytes)
		byte[] padded_packet = new byte[2 + packet.length];
		byte[] zero_pad = new byte[] { (byte) 0x00, (byte) 0x00 };
		System.arraycopy(zero_pad, 0, padded_packet, 0, 2);
		System.arraycopy(packet, 0, padded_packet, 2, packet.length);

		// Send packet
		try {
			netThread.broadcast(padded_packet);
			logMsg(receivedMessages, String.format(
					"Sent packet %d of size %d bytes", packetsSent,
					packet.length));
			packetsSent++;
		} catch (IOException e) {
			logMsg(receivedMessages, String.format(
					"Error sending packet %d of size %d bytes", packetsSent,
					packet.length));
			e.printStackTrace();
			return false;
		}

		return true;
	}

	/** Called when the activity is first created. */
	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.main);

		((Button) findViewById(R.id.config_button))
				.setOnClickListener(mClicked);

		((Button) findViewById(R.id.fifo_button)).setOnClickListener(mClicked);

		Button send_button = (Button) findViewById(R.id.data_button);
		send_button.setOnClickListener(mClicked);

		Button repeat_button = (Button) findViewById(R.id.repeat_button);
		repeat_button.setOnClickListener(mClicked);

		receivedMessages = new ArrayAdapter<String>(this, R.layout.message);
		((ListView) findViewById(R.id.msgList)).setAdapter(receivedMessages);

		receivedMessages2 = new ArrayAdapter<String>(this, R.layout.message);
		((ListView) findViewById(R.id.msgList2)).setAdapter(receivedMessages2);

		logMsg(receivedMessages, "*** Application started ***");

		StrictMode.setThreadPolicy(new StrictMode.ThreadPolicy.Builder()
				.permitAll().build());

		// Setup writing to log file on sd card
		String state = Environment.getExternalStorageState();
		if (Environment.MEDIA_MOUNTED.equals(state)) {
			// We can read and write the media
			logFile = new File(Environment.getExternalStorageDirectory(),
					String.format("sonar-%d.txt", System.currentTimeMillis()));
			try {
				logWriter = new PrintWriter(logFile);
				logMsg(receivedMessages, "*** Opened log file for writing ***");
			} catch (Exception e) {
				logWriter = null;
				logMsg(receivedMessages,
						"*** Couldn't open log file for writing ***");
			}
		} else if (Environment.MEDIA_MOUNTED_READ_ONLY.equals(state)) {
			// We can only read the media
		} else {
			// One of many other states, but we can neither read nor write
		}

		// Get a wakelock to keep everything running
		PowerManager pm = (PowerManager) getApplicationContext()
				.getSystemService(Context.POWER_SERVICE);
		wakeLock = pm.newWakeLock(PowerManager.FULL_WAKE_LOCK
				| PowerManager.ON_AFTER_RELEASE, TAG);

		// Get a multicast lock???
		WifiManager wifi = (WifiManager) this
				.getSystemService(Context.WIFI_SERVICE);
		mcLock = wifi.createMulticastLock("edu.mit.csail.jasongao.sonar");

		// Location / GPS
		locManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);

		// Extract binaries
		File localTcpdump = getFileStreamPath(TCPDUMP);
		if (localTcpdump.exists()) {
			logMsg(receivedMessages, "binary already exists at "
					+ getFileStreamPath(TCPDUMP));
		} else {
			extractBinary(R.raw.tcpdump, TCPDUMP);
		}

		// Start the network thread and ensure it's running
		netThread = new NetworkThread(myHandler);
		if (!netThread.socketIsOK()) {
			Log.e(TAG, "Cannot start server: socket not ok.");
			return; // quit out
		}
		netThread.start();
		if (netThread.localAddress == null) {
			Log.e(TAG, "Couldn't get my IP address.");
			return; // quit out
		}

		logMsg(receivedMessages,
				"iface=" + NetworkThread.IFACE);
		logMsg(receivedMessages,
				"localAddress=" + netThread.localAddress.getHostAddress());
		logMsg(receivedMessages, "broadcastAddress="
				+ netThread.broadcastAddress.getHostAddress());

		startTcpdump();
	}

	/** Always called after onStart, even if activity is not paused. */
	@Override
	protected void onResume() {
		super.onResume();
		wakeLock.acquire();
		mcLock.acquire();
	}

	@Override
	protected void onPause() {
		// stop recurring runnables
		// TODO

		mcLock.release();
		wakeLock.release();
		super.onPause();
	}

	@Override
	public void onDestroy() {
		repeatingPacketStop();
		stopTcpdump();
		netThread.closeSocket();

		logWriter.flush();
		logWriter.close();

		super.onDestroy();
	}

	private void startTcpdump() {
		try {
			tcpdumpCmd = new CaptureThread(getFileStreamPath(TCPDUMP)
					.toString()
					+ " -neql -tt -xx -s 256 -i "
					+ NetworkThread.IFACE, myHandler);
			tcpdumpCmd.start();
		} catch (IOException e) {
			e.printStackTrace();
			Log.e(TAG, "error starting tcpdump", e);
		}
	}

	private void stopTcpdump() {
		if (tcpdumpCmd != null) {
			tcpdumpCmd.interrupt();
			tcpdumpCmd = null;
			try {
				CaptureThread ec = new CaptureThread("killall " + TCPDUMP);
				ec.start();
				ec.join();
			} catch (IOException e) {
				e.printStackTrace();
				Log.e(TAG, "error killing tcpdump", e);
			} catch (InterruptedException e) {
				// swallow error
			}
		}
	}

	private void extractBinary(int id, String fileName) {
		/*
		 * extracts the binary from the apk and makes it executable. If any step
		 * fails and the function continues to run everything should be cleaned
		 * up
		 */
		final InputStream arpBin = getResources().openRawResource(id);
		FileOutputStream out = null;
		boolean success = true;
		final byte[] buff = new byte[4096];
		try {
			out = openFileOutput(fileName, Context.MODE_PRIVATE);
			while (arpBin.read(buff) > 0)
				out.write(buff);
		} catch (FileNotFoundException e) {
			Log.e(TAG, fileName + "wasn't found", e);
			success = false;
		} catch (IOException e) {
			Log.e(TAG, "couldn't extract executable", e);
			success = false;
		} finally {
			try {
				out.close();
			} catch (IOException e) {
				// swallow error
			}
		}
		try {
			CaptureThread ec = new CaptureThread("chmod 770 "
					+ getFileStreamPath(fileName).toString());
			ec.start();
			ec.join();
		} catch (IOException e) {
			Log.e(TAG, "error running chmod on local file", e);
			success = false;
		} catch (InterruptedException e) {
			Log.i(TAG, "thread running chmod was interrupted");
			success = false;
		} finally {
			if (!success) {
				getFileStreamPath(fileName).delete();
			} else {
				logMsg(receivedMessages,
						"successfully extracted executable to "
								+ getFileStreamPath(fileName));
			}
		}
	}

	// Buttons
	private final OnClickListener mClicked = new OnClickListener() {
		public void onClick(View v) {
			switch (v.getId()) {

			case R.id.data_button:
				sendConfigPackets = false;
				sendGetFifoCountPacket = false;
				sendData();
				break;
			case R.id.config_button:
				sendConfigPackets = true;
				sendGetFifoCountPacket = false;
				sendData();
				break;
			case R.id.fifo_button:
				sendConfigPackets = false;
				sendGetFifoCountPacket = true;
				sendData();
				break;
			case R.id.repeat_button:
				if (!currentlyRepeating) {
					currentlyRepeating = true;
					repeatingPacketStart();
					((Button) findViewById(R.id.repeat_button))
							.setText("STOP loop");
				} else {
					currentlyRepeating = false;
					repeatingPacketStop();
					((Button) findViewById(R.id.repeat_button))
							.setText("START loop");
				}
			default:
				break;
			}
		}
	};
}