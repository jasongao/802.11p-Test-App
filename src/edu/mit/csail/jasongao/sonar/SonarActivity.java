package edu.mit.csail.jasongao.sonar;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.util.Random;

import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.DefaultHttpClient;

import android.app.Activity;
import android.content.Context;
import android.location.LocationManager;
import android.os.AsyncTask;
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
import android.widget.ListView;

public class SonarActivity extends Activity {
	final static private String TAG = "SonarActivity";

	// Attributes
	private long nodeId = -1;

	// Wifi Test
	private int testLength = 4000;
	private long packetsSent = 0;
	private long packetsReceived = 0;
	private long packetsToStopAt = 100;
	private long timeStarted;
	private long timeStopped;
	private long messagesEveryPackets = 100;

	// Mobile data test
	int gs = 1000; // initial upload size
	int ds = 1000; // initial download size

	// UI
	Button wifi_button, download_button, upload_button, download_button_2,
			upload_button_2;
	ListView msgList;
	ArrayAdapter<String> receivedMessages;

	// Logging to file
	File logFile;
	PrintWriter logWriter;

	// Network
	private NetworkThread netThread;

	// Android components
	PowerManager.WakeLock wakeLock = null;
	LocationManager locManager;

	private boolean recurringPingEnabled = false;
	private boolean recurringPingNoDelay = false;
	private long recurringPingPeriod = 7L * testLength / 900 * packetsToStopAt
			/ 1000;

	// Keep sending a ping packet
	private Runnable recurringPingR = new Runnable() {
		@Override
		public void run() {
			sendPing();
			if (packetsSent >= packetsToStopAt) {
				stopRecurringPing();
			}
			if (recurringPingEnabled && recurringPingNoDelay) {
				myHandler.post(recurringPingR);
			} else if (recurringPingEnabled) {
				myHandler.postDelayed(recurringPingR, recurringPingPeriod);
			}
		}
	};

	private class UploadFilesTask extends AsyncTask<Integer, Integer, Long> {
		protected Long doInBackground(Integer... sizes) {

			for (int i = 0; i < sizes.length; i++) {
				int size = sizes[i];
				String path = "http://128.30.87.195:60000/upload/";

				DefaultHttpClient httpclient = new DefaultHttpClient();
				HttpPost httpost = new HttpPost(path);

				byte[] r = new byte[size];
				Random rand = new Random();
				rand.nextBytes(r);
				String s = new String(r);

				try {
					StringEntity se = new StringEntity(s);
					httpost.setEntity(se);
					httpost.setHeader("Accept", "application/json");
					httpost.setHeader("Content-type", "application/json");

					HttpResponse response = httpclient.execute(httpost);

					InputStreamReader isr = new InputStreamReader(response
							.getEntity().getContent());
					BufferedReader br = new BufferedReader(isr);
					if (br.readLine().equals("POST OK")) {
						publishProgress(size);
					} else {
						publishProgress(-1);
					}

				} catch (Exception e) {
					e.printStackTrace();
					publishProgress(-1);
				}

			}

			return (long) sizes.length;
		}

		protected void onProgressUpdate(Integer... progress) {
			logMsg(String.format("Uploaded %d bytes over mobile data.",
					progress));
		}

		protected void onPostExecute(Long result) {
			logMsg(String.format("Finished %d uploads.", result));
		}
	}

	private class DownloadFilesTask extends AsyncTask<Integer, Long, Long> {
		protected Long doInBackground(Integer... sizes) {

			for (int i = 0; i < sizes.length; i++) {
				int size = sizes[i];
				String path = String.format(
						"http://128.30.87.195:60000/static/%d.bin", size);

				DefaultHttpClient httpclient = new DefaultHttpClient();
				HttpGet httpget = new HttpGet(path);

				try {
					HttpResponse response = httpclient.execute(httpget);
					long downloadLength = response.getEntity()
							.getContentLength();
					publishProgress(downloadLength);
				} catch (Exception e) {
					e.printStackTrace();
					publishProgress(-1L);
				}

			}

			return (long) sizes.length;
		}

		protected void onProgressUpdate(Long... progress) {
			logMsg(String.format("Downloaded %d bytes over mobile data.",
					progress));
		}

		protected void onPostExecute(Long result) {
			logMsg(String.format("Finished %d uploads.", result));
		}
	}

	// Handler message types
	protected final static int LOG = 3;
	protected final static int PACKET_RECV = 4;

	/** Handle messages from various components */
	private final Handler myHandler = new Handler() {
		@Override
		public void handleMessage(Message msg) {
			switch (msg.what) {
			case PACKET_RECV:
				packetsReceived++;
				if (packetsReceived % messagesEveryPackets == 0) {
					logMsg(String.format("Received packet %d", packetsReceived));
				}
				// byte[] pingBytes = (byte[]) msg.obj;
				// logMsg(String.format("Received packet %d of size %d",
				// packetsReceived, pingBytes.length));
				break;
			case LOG: // Write a string to log file and UI log display
				logMsg((String) msg.obj);
				break;
			}
		}
	};

	/** Log message and also display on screen */
	public void logMsg(String line) {
		line = String.format("%d: %s", System.currentTimeMillis(), line);
		Log.i(TAG, line);
		receivedMessages.add((String) line);
		if (logWriter != null) {
			logWriter.println((String) line);
		}
	}

	/** Send ping */
	private void sendPing() {
		byte[] data = new byte[testLength];
		try {
			netThread.sendData(data);
			packetsSent++;
			if (packetsSent % messagesEveryPackets == 0) {
				logMsg(String.format("Sent packet %d of size %d bytes",
						packetsSent, testLength));
			}
		} catch (IOException e) {
			logMsg(String.format("Error sending packet %d of size %d bytes",
					packetsSent, testLength));
			e.printStackTrace();
		}
	}

	/** Called when the activity is first created. */
	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.main);

		wifi_button = (Button) findViewById(R.id.wifi_button);
		wifi_button.setOnClickListener(wifi_button_listener);

		download_button = (Button) findViewById(R.id.download_button);
		download_button.setOnClickListener(download_button_listener);

		upload_button = (Button) findViewById(R.id.upload_button);
		upload_button.setOnClickListener(upload_button_listener);

		download_button_2 = (Button) findViewById(R.id.download_button_2);
		download_button_2.setOnClickListener(download_button_listener_2);

		upload_button_2 = (Button) findViewById(R.id.upload_button_2);
		upload_button_2.setOnClickListener(upload_button_listener_2);

		msgList = (ListView) findViewById(R.id.msgList);
		receivedMessages = new ArrayAdapter<String>(this, R.layout.message);
		msgList.setAdapter(receivedMessages);

		logMsg("*** Application started ***");

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
				logMsg("*** Opened log file for writing ***");
			} catch (Exception e) {
				logWriter = null;
				logMsg("*** Couldn't open log file for writing ***");
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

		// Location / GPS
		locManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);

		// Start the network thread and ensure it's running
		netThread = new NetworkThread(myHandler);
		if (!netThread.socketIsOK()) {
			Log.e(TAG, "Cannot start server: socket not ok.");
			return; // quit out
		}
		netThread.start();
		if (netThread.getLocalAddress() == null) {
			Log.e(TAG, "Couldn't get my IP address.");
			return; // quit out
		}

		Bundle extras = getIntent().getExtras();
		if (extras != null && extras.containsKey("id")) {
			nodeId = Long.valueOf(extras.getString("id"));
		} else {
			nodeId = 1000 * netThread.getLocalAddress().getAddress()[2]
					+ netThread.getLocalAddress().getAddress()[3];
		}

		logMsg("nodeId=" + nodeId);
	}

	/** Always called after onStart, even if activity is not paused. */
	@Override
	protected void onResume() {
		super.onResume();
		wakeLock.acquire();
	}

	@Override
	protected void onPause() {
		stopRecurringPing();
		wakeLock.release();

		super.onPause();
	}

	@Override
	public void onDestroy() {
		netThread.closeSocket();

		logWriter.flush();
		logWriter.close();

		super.onDestroy();
	}

	private void startRecurringPing() {
		recurringPingEnabled = true;
		wifi_button.setText("Stop WiFi");
		packetsSent = 0;
		timeStarted = System.currentTimeMillis();
		logMsg("Starting WiFi benchmark at " + timeStarted);
		myHandler.post(recurringPingR);
	}

	private void stopRecurringPing() {
		recurringPingEnabled = false;
		wifi_button.setText("Start WiFi");
		myHandler.removeCallbacks(recurringPingR);
		timeStopped = System.currentTimeMillis();
		logMsg(String.format("Sent %d packets in %d ms", packetsSent,
				timeStopped - timeStarted));
		testLength *= 2;
	}

	private OnClickListener wifi_button_listener = new OnClickListener() {
		public void onClick(View v) {
			if (recurringPingEnabled) {
				stopRecurringPing();
			} else {
				startRecurringPing();
			}
		}
	};

	private OnClickListener upload_button_listener = new OnClickListener() {
		public void onClick(View v) {
			int s = 710;
			new UploadFilesTask().execute(s, s, s, s, s, s, s, s, s, s, s, s,
					s, s, s, s, s, s, s, s, s, s, s, s, s, s, s, s, s, s, s, s,
					s, s, s, s, s, s, s, s, s, s, s, s, s, s, s, s, s, s, s, s,
					s, s, s, s, s, s, s, s, s, s, s, s, s, s, s, s, s, s, s, s,
					s, s, s, s, s, s, s, s, s, s, s, s, s, s, s, s, s, s, s, s,
					s, s, s, s, s, s, s, s);
		}
	};

	private OnClickListener download_button_listener = new OnClickListener() {
		public void onClick(View v) {
			int s = 710;
			new DownloadFilesTask().execute(s, s, s, s, s, s, s, s, s, s, s, s,
					s, s, s, s, s, s, s, s, s, s, s, s, s, s, s, s, s, s, s, s,
					s, s, s, s, s, s, s, s, s, s, s, s, s, s, s, s, s, s, s, s,
					s, s, s, s, s, s, s, s, s, s, s, s, s, s, s, s, s, s, s, s,
					s, s, s, s, s, s, s, s, s, s, s, s, s, s, s, s, s, s, s, s,
					s, s, s, s, s, s, s, s);
		}
	};

	private OnClickListener upload_button_listener_2 = new OnClickListener() {
		public void onClick(View v) {
			int s = 12919;
			new UploadFilesTask().execute(s, s, s, s, s, s, s, s, s, s, s, s,
					s, s, s, s, s, s, s, s, s, s, s, s, s, s, s, s, s, s, s, s,
					s, s, s, s, s, s, s, s, s, s, s, s, s, s, s, s, s, s, s, s,
					s, s, s, s, s, s, s, s, s, s, s, s, s, s, s, s, s, s, s, s,
					s, s, s, s, s, s, s, s, s, s, s, s, s, s, s, s, s, s, s, s,
					s, s, s, s, s, s, s, s);
		}
	};

	private OnClickListener download_button_listener_2 = new OnClickListener() {
		public void onClick(View v) {
			int s = 12919;
			new DownloadFilesTask().execute(s, s, s, s, s, s, s, s, s, s, s, s,
					s, s, s, s, s, s, s, s, s, s, s, s, s, s, s, s, s, s, s, s,
					s, s, s, s, s, s, s, s, s, s, s, s, s, s, s, s, s, s, s, s,
					s, s, s, s, s, s, s, s, s, s, s, s, s, s, s, s, s, s, s, s,
					s, s, s, s, s, s, s, s, s, s, s, s, s, s, s, s, s, s, s, s,
					s, s, s, s, s, s, s, s);
		}
	};

	private OnClickListener upload_button_listener_auto = new OnClickListener() {
		public void onClick(View v) {
			new UploadFilesTask().execute(gs, gs, gs, gs, gs, gs, gs, gs, gs,
					gs);
			gs *= 4;
		}
	};

	private OnClickListener download_button_listener_auto = new OnClickListener() {
		public void onClick(View v) {
			new DownloadFilesTask().execute(ds, ds, ds, ds, ds, ds, ds, ds, ds,
					ds);
			ds *= 4;
		}
	};
}