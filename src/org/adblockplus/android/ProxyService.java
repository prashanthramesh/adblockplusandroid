/*
 * This file is part of the Adblock Plus,
 * Copyright (C) 2006-2012 Eyeo GmbH
 *
 * Adblock Plus is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License version 3 as
 * published by the Free Software Foundation.
 *
 * Adblock Plus is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Adblock Plus.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.adblockplus.android;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeoutException;

import sunlabs.brazil.server.Server;
import sunlabs.brazil.util.Base64;
import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.res.Resources;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Proxy;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.StrictMode;
import android.preference.PreferenceManager;
import android.support.v4.app.NotificationCompat;
import android.util.Log;

import com.stericson.RootTools.RootTools;
import com.stericson.RootTools.RootToolsException;

public class ProxyService extends Service implements OnSharedPreferenceChangeListener
{
  private static final String LOCALHOST = "127.0.0.1";
  /**
   * Indicates that system supports native proxy configuration.
   */
  public static final boolean NATIVE_PROXY_SUPPORTED = Build.VERSION.SDK_INT >= 12; // Honeycomb 3.1

  static
  {
    RootTools.debugMode = false;
  }

  private static final String TAG = "ProxyService";
  private static final boolean logRequests = false;

  // Do not use 8080 because it is a "dirty" port, Android uses it if something goes wrong
  // First element is reserved for previously used port
  private static final int[] portVariants = new int[] {-1, 2020, 3030, 4040, 5050, 6060, 7070, 9090, 1234, 12345, 4321, 0};

  private final static int DEFAULT_TIMEOUT = 3000;
  private final static int NO_TRAFFIC_TIMEOUT = 5 * 60 * 1000; // 5 minutes

  final static int ONGOING_NOTIFICATION_ID = R.string.app_name;
  private static final long POSITION_RIGHT = Build.VERSION.SDK_INT >= Build.VERSION_CODES.GINGERBREAD ? Long.MIN_VALUE : Long.MAX_VALUE;
  private final static int NOTRAFFIC_NOTIFICATION_ID = R.string.app_name + 3;

  /**
   * Broadcasted when service starts or stops.
   */
  public final static String BROADCAST_STATE_CHANGED = "org.adblockplus.android.service.state";
  /**
   * Broadcasted if proxy fails to start.
   */
  public final static String BROADCAST_PROXY_FAILED = "org.adblockplus.android.proxy.failure";

  private final static String IPTABLES_RETURN = " -t nat -m owner --uid-owner {{UID}} -A OUTPUT -p tcp -j RETURN\n";
  private final static String IPTABLES_ADD_HTTP = " -t nat -A OUTPUT -p tcp --dport 80 -j REDIRECT --to {{PORT}}\n";

  boolean hideIcon;
  private Handler notrafficHandler;

  protected ProxyServer proxy = null;
  protected int port;
  private Properties proxyConfiguration = new Properties();

  /**
   * Indicates that service is working with root privileges.
   */
  private boolean transparent = false;
  /**
   * Indicates that service has autoconfigured Android proxy settings (version 3.1+).
   */
  private boolean nativeProxyAutoConfigured = false;
  /**
   * Indicates that Android proxy settings are correctly configured (version 4.1.2+ 4.2.2+).
   */
  private boolean proxyManualyConfigured = false;

  private String iptables = null;

  @SuppressLint("NewApi")
  @Override
  public void onCreate()
  {
    super.onCreate();

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.GINGERBREAD)
    {
      // Proxy is running in separate thread, it's just some resolution request during initialization.
      // Not worth spawning a separate thread for this.
      StrictMode.ThreadPolicy policy = new StrictMode.ThreadPolicy.Builder().permitNetwork().build();
      StrictMode.setThreadPolicy(policy);
    }

    // Get port for local proxy
    SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
    Resources resources = getResources();

    // Try to read user proxy settings
    String proxyHost = null;
    String proxyPort = null;
    String proxyExcl = null;
    String proxyUser = null;
    String proxyPass = null;

    if (NATIVE_PROXY_SUPPORTED)
    {
      // Read system settings
      proxyHost = System.getProperty("http.proxyHost");
      proxyPort = System.getProperty("http.proxyPort");
      proxyExcl = System.getProperty("http.nonProxyHosts");

      Log.d(TAG, "PRX: " + proxyHost + ":" + proxyPort + "(" + proxyExcl + ")");
      // not used but left for future reference
      String[] px = ProxySettings.getUserProxy(getApplicationContext());
      if (px != null)
        Log.d(TAG, "PRX: " + px[0] + ":" + px[1] + "(" + px[2] + ")");
    }
    else
    {
      // Read application settings
      proxyHost = prefs.getString(getString(R.string.pref_proxyhost), null);
      proxyPort = prefs.getString(getString(R.string.pref_proxyport), null);
      proxyUser = prefs.getString(getString(R.string.pref_proxyuser), null);
      proxyPass = prefs.getString(getString(R.string.pref_proxypass), null);
    }

    // Check for root privileges and try to install transparent proxy
    if (RootTools.isAccessGiven())
    {
      try
      {
        initIptables();

        StringBuffer cmd = new StringBuffer();
        int uid = getPackageManager().getPackageInfo(getPackageName(), 0).applicationInfo.uid;
        cmd.append(iptables);
        cmd.append(IPTABLES_RETURN.replace("{{UID}}", String.valueOf(uid)));
        String rules = cmd.toString();
        RootTools.sendShell(rules, DEFAULT_TIMEOUT);
        transparent = true;
      }
      catch (FileNotFoundException e)
      {
        // ignore - this is "normal" case
      }
      catch (NameNotFoundException e)
      {
        Log.e(TAG, "Failed to initialize iptables", e);
      }
      catch (IOException e)
      {
        Log.e(TAG, "Failed to initialize iptables", e);
      }
      catch (RootToolsException e)
      {
        Log.e(TAG, "Failed to initialize iptables", e);
      }
      catch (TimeoutException e)
      {
        Log.e(TAG, "Failed to initialize iptables", e);
      }
    }

    if (!transparent)
    {
      // Try to set native proxy
      nativeProxyAutoConfigured = ProxySettings.setConnectionProxy(getApplicationContext(), LOCALHOST, port, "");

      if (NATIVE_PROXY_SUPPORTED)
      {
        registerReceiver(connectionReceiver, new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION));
        registerReceiver(connectionReceiver, new IntentFilter(Proxy.PROXY_CHANGE_ACTION));
      }
    }

    // Save current native proxy situation. The service is always started on the first run so
    // we will always have a correct value from the box
    SharedPreferences.Editor editor = prefs.edit();
    editor.putBoolean(getString(R.string.pref_proxyautoconfigured), transparent || nativeProxyAutoConfigured);
    editor.commit();

    registerReceiver(proxyReceiver, new IntentFilter(ProxyService.BROADCAST_PROXY_FAILED));
    registerReceiver(filterReceiver, new IntentFilter(AdblockPlus.BROADCAST_FILTERING_CHANGE));
    registerReceiver(filterReceiver, new IntentFilter(AdblockPlus.BROADCAST_FILTER_MATCHES));

    // Start proxy
    if (proxy == null)
    {
      // Select available port and bind to it, use previously selected port by default
      portVariants[0] = prefs.getInt(getString(R.string.pref_lastport), -1);
      ServerSocket listen = null;
      String msg = null;
      for (int p : portVariants)
      {
        if (p < 0)
          continue;
        try
        {
          listen = new ServerSocket(p, 1024);
          port = p;
          break;
        }
        catch (IOException e)
        {
          Log.e(TAG, null, e);
          msg = e.getMessage();
        }
      }
      if (listen == null)
      {
        sendBroadcast(new Intent(BROADCAST_PROXY_FAILED).putExtra("msg", msg));
        return;
      }

      // Save selected port
      editor.putInt(getString(R.string.pref_lastport), port);
      editor.commit();

      // Initialize proxy
      proxyConfiguration.put("handler", "main");
      proxyConfiguration.put("main.prefix", "");
      proxyConfiguration.put("main.class", "sunlabs.brazil.server.ChainHandler");
      if (transparent)
      {
        proxyConfiguration.put("main.handlers", "urlmodifier adblock");
        proxyConfiguration.put("urlmodifier.class", "org.adblockplus.brazil.TransparentProxyHandler");
      }
      else
      {
        proxyConfiguration.put("main.handlers", "https adblock");
        proxyConfiguration.put("https.class", "org.adblockplus.brazil.SSLConnectionHandler");
      }
      proxyConfiguration.put("adblock.class", "org.adblockplus.brazil.RequestHandler");
      if (logRequests)
        proxyConfiguration.put("adblock.proxylog", "yes");

      configureUserProxy(proxyConfiguration, proxyHost, proxyPort, proxyExcl, proxyUser, proxyPass);

      proxy = new ProxyServer();
      proxy.logLevel = Server.LOG_DIAGNOSTIC;
      proxy.setup(listen, proxyConfiguration.getProperty("handler"), proxyConfiguration);
      proxy.start();
    }

    if (transparent)
    {
      // Redirect traffic via iptables
      try
      {
        StringBuffer cmd = new StringBuffer();
        cmd.append(iptables);
        cmd.append(IPTABLES_ADD_HTTP.replace("{{PORT}}", String.valueOf(port)));
        String rules = cmd.toString();
        RootTools.sendShell(rules, DEFAULT_TIMEOUT);
      }
      catch (FileNotFoundException e)
      {
        // ignore - this is "normal" case
      }
      catch (IOException e)
      {
        Log.e(TAG, "Failed to initialize iptables", e);
      }
      catch (RootToolsException e)
      {
        Log.e(TAG, "Failed to initialize iptables", e);
      }
      catch (TimeoutException e)
      {
        Log.e(TAG, "Failed to initialize iptables", e);
      }
    }

    prefs.registerOnSharedPreferenceChangeListener(this);

    // Lock service
    hideIcon = prefs.getBoolean(getString(R.string.pref_hideicon), resources.getBoolean(R.bool.def_hideicon));
    startForeground(ONGOING_NOTIFICATION_ID, getNotification());

    // If automatic setting of proxy was blocked, check if user has set it manually
    boolean manual = isManual();
    if (manual && NATIVE_PROXY_SUPPORTED)
    {
      ConnectivityManager connectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
      updateNoTrafficCheck(connectivityManager);
    }

    sendStateChangedBroadcast();
    Log.i(TAG, "Service started");
  }

  @Override
  public int onStartCommand(Intent intent, int flags, int startId)
  {
    return START_STICKY;
  }

  @Override
  public void onDestroy()
  {
    super.onDestroy();

    stopNoTrafficCheck();

    unregisterReceiver(filterReceiver);
    unregisterReceiver(proxyReceiver);

    // Stop IP redirecting
    if (transparent)
    {
      new Thread()
      {
        @Override
        public void run()
        {
          try
          {
            RootTools.sendShell(iptables + " -t nat -F OUTPUT", DEFAULT_TIMEOUT);
          }
          catch (Exception e)
          {
            Log.e(TAG, "Failed to clear iptables", e);
          }
        }
      }.start();
    }

    if (NATIVE_PROXY_SUPPORTED)
      unregisterReceiver(connectionReceiver);

    // Clear native proxy
    if (nativeProxyAutoConfigured)
    {
      clearConnectionProxy();
    }

    sendBroadcast(new Intent(BROADCAST_STATE_CHANGED).putExtra("enabled", false));

    // Stop proxy server
    if (proxy != null)
      proxy.close();

    // TODO Do we have to check current state?
    // Stop engine if not in interactive mode
    AdblockPlus.getApplication().stopEngine(false);

    // Release service lock
    stopForeground(true);

    Log.i(TAG, "Service stopped");
  }

  /**
   * Restores system proxy settings via native call on Android 3.1+ devices
   * using Java reflection.
   */
  private void clearConnectionProxy()
  {
    String proxyHost = (String) proxyConfiguration.getProperty("adblock.proxyHost");
    String proxyPort = (String) proxyConfiguration.getProperty("adblock.proxyPort");
    String proxyExcl = (String) proxyConfiguration.getProperty("adblock.proxyExcl");
    int port = 0;
    try
    {
      if (proxyHost != null)
        port = Integer.valueOf(proxyPort);
    }
    catch (NumberFormatException e)
    {
      Log.e(TAG, "Bad port setting", e);
    }
    ProxySettings.setConnectionProxy(getApplicationContext(), proxyHost, port, proxyExcl);
  }

  /**
   * Sets user proxy settings in proxy service properties.
   */
  private void configureUserProxy(Properties config, String proxyHost, String proxyPort, String proxyExcl, String proxyUser, String proxyPass)
  {
    // Clean previous settings
    config.remove("adblock.proxyHost");
    config.remove("adblock.proxyPort");
    config.remove("adblock.auth");
    config.remove("adblock.proxyExcl");
    if (!transparent)
    {
      config.remove("https.proxyHost");
      config.remove("https.proxyPort");
      config.remove("https.auth");
    }

    if (nativeProxyAutoConfigured)
      passProxySettings(proxyHost, proxyPort, proxyExcl);

    // Check if there are any settings
    if (proxyHost == null || "".equals(proxyHost))
      return;

    // Check for dirty proxy settings - this indicated previous crash:
    // proxy points to ourselves
    // proxy port is null, 0 or not a number
    // proxy is 127.0.0.1:8080
    if (proxyPort == null)
      return;
    int p = 0;
    try
    {
      p = Integer.valueOf(proxyPort);
    }
    catch (NumberFormatException e)
    {
      return;
    }
    if (p == 0 || isLocalHost(proxyHost) && (p == port || p == 8080))
    {
      if (nativeProxyAutoConfigured)
        passProxySettings(null, null, null);
      return;
    }

    config.put("adblock.proxyHost", proxyHost);
    config.put("adblock.proxyPort", proxyPort);
    if (!transparent)
    {
      config.put("https.proxyHost", proxyHost);
      config.put("https.proxyPort", proxyPort);
    }

    // TODO Not implemented in our proxy but needed to restore settings
    if (proxyExcl != null)
      config.put("adblock.proxyExcl", proxyExcl);

    if (proxyUser != null && !"".equals(proxyUser) && proxyPass != null && !"".equals(proxyPass))
    {
      // Base64 encode user:password
      String proxyAuth = "Basic " + new String(Base64.encode(proxyUser + ":" + proxyPass));
      config.put("adblock.auth", proxyAuth);
      if (!transparent)
        config.put("https.auth", proxyAuth);
    }
  }

  private void passProxySettings(String proxyHost, String proxyPort, String proxyExcl)
  {
    try
    {
      CrashHandler handler = (CrashHandler) Thread.getDefaultUncaughtExceptionHandler();
      handler.saveProxySettings(proxyHost, proxyPort, proxyExcl);
    }
    catch (ClassCastException e)
    {
      // ignore - default handler in use
    }
  }

  @Override
  public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key)
  {
    if (NATIVE_PROXY_SUPPORTED)
    {
      String ketHost = getString(R.string.pref_proxyhost);
      String keyPort = getString(R.string.pref_proxyport);
      String keyUser = getString(R.string.pref_proxyuser);
      String keyPass = getString(R.string.pref_proxypass);
      if (key.equals(ketHost) || key.equals(keyPort) || key.equals(keyUser) || key.equals(keyPass))
      {
        String proxyHost = sharedPreferences.getString(ketHost, null);
        String proxyPort = sharedPreferences.getString(keyPort, null);
        String proxyUser = sharedPreferences.getString(keyUser, null);
        String proxyPass = sharedPreferences.getString(keyPass, null);
        if (proxy != null)
        {
          configureUserProxy(proxyConfiguration, proxyHost, proxyPort, null, proxyUser, proxyPass);
          proxy.restart(proxyConfiguration.getProperty("handler"));
        }
      }
    }
  }

  public boolean isTransparent()
  {
    return transparent;
  }

  public boolean isNativeProxyAutoConfigured()
  {
    return nativeProxyAutoConfigured;
  }

  /**
   * Checks if user has to set proxy settings manually
   */
  public boolean isManual()
  {
    return !transparent && !nativeProxyAutoConfigured;
  }

  /**
   * Checks whether traffic check is pending
   */
  public boolean noTraffic()
  {
    return notrafficHandler != null;
  }

  /**
   * Checks if specified host is local.
   */
  private static final boolean isLocalHost(String host)
  {
    if (host == null)
      return false;

    try
    {
      if (host.equalsIgnoreCase("localhost"))
        return true;

      String className = "android.net.NetworkUtils";
      Class<?> c = Class.forName(className);
      /*
       * InetAddress address = NetworkUtils.numericToInetAddress(host);
       */
      Method method = c.getMethod("numericToInetAddress", String.class);
      InetAddress address = (InetAddress) method.invoke(null, host);

      if (address.isLoopbackAddress())
        return true;
    }
    catch (Exception e)
    {
      Log.w(TAG, null, e);
    }
    return false;
  }

  /**
   * Initializes iptables executable.
   * 
   * @throws FileNotFoundException
   *           If iptables initialization failed due to provided reasons.
   */
  private void initIptables() throws IOException, RootToolsException, TimeoutException, FileNotFoundException
  {
    if (!RootTools.isAccessGiven())
      throw new FileNotFoundException("No root access");

    File ipt = getFileStreamPath("iptables");

    if (!ipt.exists())
    {
      Log.e(TAG, "No iptables excutable found");
      throw new FileNotFoundException("No iptables executable");
    }

    String path = ipt.getAbsolutePath();

    RootTools.sendShell("chmod 700 " + path, DEFAULT_TIMEOUT);

    boolean compatible = false;
    boolean version = false;

    String command = path + " --version\n" + path + " -L -t nat -n\n";

    List<String> result = RootTools.sendShell(command, DEFAULT_TIMEOUT);
    for (String line : result)
    {
      if (line.contains("OUTPUT"))
        compatible = true;
      if (line.contains("v1.4."))
        version = true;
    }

    if (!compatible || !version)
    {
      Log.e(TAG, "Incompatible iptables excutable");
      throw new FileNotFoundException("Incompatible iptables excutable");
    }

    iptables = path;
  }

  public List<String> getIptablesOutput()
  {
    if (iptables == null)
      return null;

    String command = iptables + " -L -t nat -n\n";
    try
    {
      return RootTools.sendShell(command, DEFAULT_TIMEOUT);
    }
    catch (Exception e)
    {
      Log.e(TAG, "Failed to get iptables configuration", e);
      return null;
    }
  }

  /**
   * Raises or removes no traffic notification based on current link proxy
   * settings
   */
  private void updateNoTrafficCheck(ConnectivityManager connectivityManager)
  {
    try
    {
      Object pp = ProxySettings.getActiveLinkProxy(connectivityManager);
      String[] userProxy = ProxySettings.getUserProxy(pp);
      if (userProxy != null)
        Log.i(TAG, "Proxy settings: " + userProxy[0] + ":" + userProxy[1] + "(" + userProxy[2] + ")");
      updateNoTrafficCheck(userProxy);
    }
    catch (Exception e)
    {
      // This should not happen
      Log.e(TAG, null, e);
    }
  }

  /**
   * Raises or removes no traffic notification based on the user proxy settings
   */
  private void updateNoTrafficCheck(String[] userProxy)
  {
    boolean ourProxy = userProxy != null && isLocalHost(userProxy[0]) && Integer.valueOf(userProxy[1]) == port;
    if (ourProxy != proxyManualyConfigured)
    {
      proxyManualyConfigured = ourProxy;
      sendStateChangedBroadcast();
    }
    if (ourProxy)
    {
      stopNoTrafficCheck();
    }
    else
    {
      // Initiate no traffic check
      notrafficHandler = new Handler();
      notrafficHandler.postDelayed(noTraffic, NO_TRAFFIC_TIMEOUT);
    }
    NotificationManager notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
    notificationManager.notify(ONGOING_NOTIFICATION_ID, getNotification());
  }

  /**
   * Stops no traffic check and resets notification message.
   */
  private void stopNoTrafficCheck()
  {
    if (notrafficHandler != null)
    {
      notrafficHandler.removeCallbacks(noTraffic);
      sendStateChangedBroadcast();
      NotificationManager notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
      notificationManager.notify(ONGOING_NOTIFICATION_ID, getNotification());
      notificationManager.cancel(NOTRAFFIC_NOTIFICATION_ID);
    }
    notrafficHandler = null;
  }

  @SuppressLint("NewApi")
  private Notification getNotification()
  {
    boolean filtering = AdblockPlus.getApplication().isFilteringEnabled();

    int msgId = R.string.notif_waiting;
    if (nativeProxyAutoConfigured || proxyManualyConfigured)
      msgId = filtering ? R.string.notif_wifi : R.string.notif_wifi_nofiltering;
    if (transparent)
      msgId = R.string.notif_all;

    NotificationCompat.Builder builder = new NotificationCompat.Builder(this);
    if (hideIcon && msgId != R.string.notif_waiting)
    {
      builder.setWhen(POSITION_RIGHT);
      builder.setSmallIcon(R.drawable.transparent);
      //builder.setContent(new RemoteViews(getPackageName(), R.layout.notif_hidden));
    }
    else
    {
      builder.setWhen(0);
      builder.setSmallIcon(R.drawable.ic_stat_blocking);
    }
    PendingIntent contentIntent = PendingIntent.getActivity(this, 0, new Intent(this, Preferences.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK), 0);
    builder.setContentIntent(contentIntent);
    builder.setContentTitle(getText(R.string.app_name));
    builder.setContentText(getString(msgId, port));
    builder.setOngoing(true);

    Notification notification = builder.getNotification();
    return notification;
  }

  public void setEmptyIcon(boolean hide)
  {
    hideIcon = hide;
    NotificationManager notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
    notificationManager.notify(ONGOING_NOTIFICATION_ID, getNotification());
  }

  public void sendStateChangedBroadcast()
  {
    Log.i(TAG, "Broadcasting " + BROADCAST_STATE_CHANGED);
    boolean manual = isManual();
    Intent stateIntent = new Intent(BROADCAST_STATE_CHANGED).putExtra("enabled", true).putExtra("port", port).putExtra("manual", manual);
    if (manual)
      stateIntent.putExtra("configured", proxyManualyConfigured);
    sendBroadcast(stateIntent);
  }

  private final IBinder binder = new LocalBinder();

  public final class LocalBinder extends Binder
  {
    public ProxyService getService()
    {
      return ProxyService.this;
    }
  }

  @Override
  public IBinder onBind(Intent intent)
  {
    return binder;
  }

  /**
   * Executed if no traffic is detected after a period of time. Notifies user
   * about possible configuration problems.
   */
  private Runnable noTraffic = new Runnable()
  {
    public void run()
    {
      // It's weird but notrafficHandler.removeCallbacks(noTraffic) does not remove this callback
      if (notrafficHandler == null)
        return;
      // Show warning notification
      NotificationCompat.Builder builder = new NotificationCompat.Builder(ProxyService.this);
      builder.setSmallIcon(R.drawable.ic_stat_warning);
      builder.setWhen(System.currentTimeMillis());
      builder.setAutoCancel(true);
      builder.setDefaults(Notification.DEFAULT_SOUND);
      Intent intent = new Intent(ProxyService.this, ConfigurationActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
      intent.putExtra("port", port);
      PendingIntent contentIntent = PendingIntent.getActivity(ProxyService.this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT);
      builder.setContentIntent(contentIntent);
      builder.setContentTitle(getText(R.string.app_name));
      builder.setContentText(getText(R.string.notif_notraffic));
      NotificationManager notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
      notificationManager.notify(NOTRAFFIC_NOTIFICATION_ID, builder.getNotification());
    }
  };

  /**
   * Stops no traffic check if traffic is detected by proxy service.
   */
  private BroadcastReceiver filterReceiver = new BroadcastReceiver()
  {
    @Override
    public void onReceive(final Context context, Intent intent)
    {
      if (intent.getAction().equals(AdblockPlus.BROADCAST_FILTERING_CHANGE))
      {
        // It's rather a hack but things are happening simultaneously and we
        // receive this broadcast despite the fact we have unsubscribed from
        // it and notification is not removed because it is changed to new one
        // during removal.
        if (!ProxyService.this.isNativeProxyAutoConfigured())
        {
          NotificationManager notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
          notificationManager.notify(ONGOING_NOTIFICATION_ID, getNotification());
        }
      }
      if (intent.getAction().equals(AdblockPlus.BROADCAST_FILTER_MATCHES))
      {
        proxyManualyConfigured = true;
        stopNoTrafficCheck();
      }
    }
  };

  /**
   * Stops service if proxy fails.
   */
  private BroadcastReceiver proxyReceiver = new BroadcastReceiver()
  {
    @Override
    public void onReceive(final Context context, Intent intent)
    {
      if (intent.getAction().equals(ProxyService.BROADCAST_PROXY_FAILED))
      {
        stopSelf();
      }
    }
  };

  /**
   * Monitors system network connection settings changes and updates proxy
   * settings accordingly.
   */
  private BroadcastReceiver connectionReceiver = new BroadcastReceiver()
  {
    @Override
    public void onReceive(Context ctx, Intent intent)
    {
      String action = intent.getAction();
      Log.i(TAG, "Action: " + action);
      // Connectivity change
      if (ConnectivityManager.CONNECTIVITY_ACTION.equals(action))
      {
        ConnectivityManager connectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo info = connectivityManager.getActiveNetworkInfo();
        if (info == null)
          return;
        String typeName = info.getTypeName();
        String subtypeName = info.getSubtypeName();
        boolean available = info.isAvailable();

        Log.i(TAG, "Network Type: " + typeName + ", subtype: " + subtypeName + ", available: " + available);
        if (info.getType() == ConnectivityManager.TYPE_WIFI)
        {
          if (nativeProxyAutoConfigured)
          {
            ProxySettings.setConnectionProxy(getApplicationContext(), LOCALHOST, port, "");
          }
          else
          {
            updateNoTrafficCheck(connectivityManager);
          }
        }
      }
      // Proxy change
      else if (Proxy.PROXY_CHANGE_ACTION.equals(action))
      {
        Object pp = intent.getParcelableExtra("proxy");
        try
        {
          String[] userProxy = ProxySettings.getUserProxy(pp);
          if (nativeProxyAutoConfigured)
          {
            if (userProxy != null && Integer.valueOf(userProxy[1]) != port)
            {
              Log.i(TAG, "User has set new proxy: " + userProxy[0] + ":" + userProxy[1] + "(" + userProxy[2] + ")");
              if (proxy != null)
              {
                configureUserProxy(proxyConfiguration, userProxy[0], userProxy[1], userProxy[2], null, null);
                proxy.restart(proxyConfiguration.getProperty("handler"));
              }
            }
          }
          else
          {
            Log.i(TAG, "User has set proxy: " + userProxy[0] + ":" + userProxy[1] + "(" + userProxy[2] + ")");
            updateNoTrafficCheck(userProxy);
          }
        }
        catch (Exception e)
        {
          // This should not happen
          Log.e(TAG, null, e);
        }
      }
    }
  };

  final class ProxyServer extends Server
  {
    @Override
    public void close()
    {
      try
      {
        listen.close();
        this.interrupt();
        this.join();
      }
      catch (Exception e)
      {
        // ignore - it always happens
      }
      log(LOG_WARNING, null, "server stopped");
    }

    @Override
    public void log(int level, Object obj, String message)
    {
      if (level <= logLevel)
      {
        Log.println(7 - level, obj != null ? obj.toString() : TAG, message);
      }
    }
  }
}
