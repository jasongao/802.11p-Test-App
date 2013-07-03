/*
 * SystemHelper.java executed superuser commands Copyright (C) 2011 Andreas Koch
 * <koch.trier@gmail.com>
 * 
 * This software was supported by the University of Trier
 * 
 * This program is free software; you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation; either version 3 of the License, or (at your option) any later
 * version.
 * 
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU General Public License for more
 * details.
 * 
 * You should have received a copy of the GNU General Public License along with
 * this program; if not, write to the Free Software Foundation, Inc., 51
 * Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA.
 */

package edu.mit.csail.jasongao.sonar;

import java.io.BufferedReader;
import java.io.InputStreamReader;

import android.util.Log;

public class SystemHelper {

	static Process process = null;
	
	public static boolean execSUCommand(String command, boolean debug) {
		try {
			if (process == null || process.getOutputStream() == null) {
				process = new ProcessBuilder().command("su").start();
			}
			if (debug) {
				Log.d("SystemHelper", "Command: " + command);
			}
			process.getOutputStream().write((command + "\n").getBytes("ASCII"));
			process.getOutputStream().flush();				
			if (debug) {
				StringBuffer sb = new StringBuffer();
				BufferedReader bre = new BufferedReader(new InputStreamReader(process.getErrorStream()));
				Thread.sleep(10);
				while (bre.ready()) {
					sb.append(bre.readLine());
				}
				String s = sb.toString();
				if (!s.replaceAll(" ", "").equalsIgnoreCase("")) {
					Log.e("SystemHelper", "Error with command: " + s);
					return false;
				}
				sb = new StringBuffer();
				BufferedReader br = new BufferedReader(new InputStreamReader(process.getInputStream()));
				Thread.sleep(10);
				while (br.ready()) {
					sb.append(br.readLine());
				}
				s = sb.toString();
				if (!s.replaceAll(" ", "").equalsIgnoreCase("")) {
					Log.e("SystemHelper", "Output from command: " + s);
					return false;
				}
			}
			Thread.sleep(100);
			return true;
		} catch (Exception e) {
			Log.e("SystemHelper", "Error executing: " + command, e);
			return false;
		}
	}
	
	public static void execNewSUCommand(String command, boolean debug) {
		try {
			if (debug) {
				Log.d("SystemHelper", "Command: " + command);
			}
			Process process = new ProcessBuilder().command("su").start();
			process.getOutputStream().write((command + "\n").getBytes("ASCII"));
			process.getOutputStream().flush();				
			if (debug) {
				StringBuffer sb = new StringBuffer();
				BufferedReader bre = new BufferedReader(new InputStreamReader(process.getErrorStream()));
				Thread.sleep(10);
				while (bre.ready()) {
					sb.append(bre.readLine());
				}
				String s = sb.toString();
				if (!s.replaceAll(" ", "").equalsIgnoreCase("")) {
					Log.e("SystemHelper", "Error with command: " + sb.toString());
				}
				sb = new StringBuffer();
				BufferedReader br = new BufferedReader(new InputStreamReader(process.getInputStream()));
				Thread.sleep(10);
				while (br.ready()) {
					sb.append(br.readLine());
				}
				s = sb.toString();
				if (!s.replaceAll(" ", "").equalsIgnoreCase("")) {
					Log.e("SystemHelper", "Output from command: " + s);
				}
			}
			Thread.sleep(100);
		} catch (Exception e) {
			Log.e("SystemHelper", "Error executing: " + command, e);
		}
	}
}
