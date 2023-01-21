package de.kai_morich.simple_bluetooth_le_terminal;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Calendar;

/**
 * create notification and queue serial data while activity is not in the foreground
 * use listener chain: SerialSocket -> SerialService -> UI fragment
 */
public class SerialService extends Service implements SerialListener {

    class SerialBinder extends Binder {
        SerialService getService() { return SerialService.this; }
    }

    private enum QueueType {Connect, ConnectError, Read, IoError}

    private static class QueueItem {
        QueueType type;
        ArrayDeque<byte[]> datas;
        Exception e;

        QueueItem(QueueType type) { this.type=type; if(type==QueueType.Read) init(); }
        QueueItem(QueueType type, Exception e) { this.type=type; this.e=e; }
        QueueItem(QueueType type, ArrayDeque<byte[]> datas) { this.type=type; this.datas=datas; }

        void init() { datas = new ArrayDeque<>(); }
        void add(byte[] data) { datas.add(data); }
    }

    private final Handler mainLooper;
    private final IBinder binder;
    private final ArrayDeque<QueueItem> queue1, queue2;
    private final QueueItem lastRead;

    private SerialSocket socket;
    private SerialListener listener;
    private boolean connected;
    private String macAddress;

    private int reconnectTimeout = 1000 * 60 * 5; // 5 minutes
    long retryConnectionStartTime = 0;

    /**
     * Lifecylce
     */
    public SerialService() {
        mainLooper = new Handler(Looper.getMainLooper());
        binder = new SerialBinder();
        queue1 = new ArrayDeque<>();
        queue2 = new ArrayDeque<>();
        lastRead = new QueueItem(QueueType.Read);
    }

    @Override
    public void onDestroy() {
        cancelNotification();
        disconnect();
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        int startFlag = super.onStartCommand(intent, flags, startId);
        String command = intent.getStringExtra("command");
        if (command != null && command.equalsIgnoreCase("connect") && intent.hasExtra("macAddress")) {
            String macAddress = intent.getStringExtra("macAddress");
            reconnectTimeout = intent.getIntExtra("reconnectTimeout", reconnectTimeout);
            if (connected) {
                sendTaskerDebugIntent(String.format("Already connected to MAC address: [%s], disconnecting first", this.macAddress));
                disconnect();
            }
            sendTaskerDebugIntent(String.format("Establishing new BLE connection; macAddress: [%s], reconnectTimeout: [%d] ms", macAddress, reconnectTimeout));
            connectToMac(macAddress);
            createNotification();
        } else if (command != null && command.equalsIgnoreCase("disconnect")) {
            sendTaskerDebugIntent("Stopping BLE service");
            disconnect();
            stopSelf();
        } else if (command != null && command.equalsIgnoreCase("send")) {
            String text = intent.getStringExtra("text");
            if (text == null) {
                sendTaskerDebugIntent("No text extra, unable to send");
                return startFlag;
            }
            sendString(text);
        }
        return startFlag;
    }

    /**
     * Api
     */
    public void connect(SerialSocket socket) throws IOException {
        socket.connect(this);
        this.socket = socket;
        connected = true;
    }

    private void connectToMac(String macAddress) {
        sendTaskerDebugIntent(String.format("Connecting to MAC address: [%s]...", macAddress));
        this.macAddress = macAddress;
        try {
            BluetoothAdapter bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
            BluetoothDevice device = bluetoothAdapter.getRemoteDevice(macAddress);
            SerialSocket socket = new SerialSocket(getApplicationContext(), device);
            connect(socket);
        } catch (Exception e) {
            onSerialConnectError(e);
        }
    }

    public void disconnect() {
        sendTaskerDebugIntent("Disconnecting");
        macAddress = null; // Prevents reconnecting
        connected = false; // ignore data,errors while disconnecting
        cancelNotification();
        if(socket != null) {
            socket.disconnect();
            socket = null;
            sendTaskerEventIntent("disconnected");
        }
        stopSelf();
    }

    public void write(byte[] data) throws IOException {
        if(!connected)
            throw new IOException("not connected");
        socket.write(data);
    }

    public void attach(SerialListener listener) {
        if(Looper.getMainLooper().getThread() != Thread.currentThread())
            throw new IllegalArgumentException("not in main thread");
        cancelNotification();
        // use synchronized() to prevent new items in queue2
        // new items will not be added to queue1 because mainLooper.post and attach() run in main thread
        synchronized (this) {
            this.listener = listener;
        }
        for(QueueItem item : queue1) {
            switch(item.type) {
                case Connect:       listener.onSerialConnect      (); break;
                case ConnectError:  listener.onSerialConnectError (item.e); break;
                case Read:          listener.onSerialRead         (item.datas); break;
                case IoError:       listener.onSerialIoError      (item.e); break;
            }
        }
        for(QueueItem item : queue2) {
            switch(item.type) {
                case Connect:       listener.onSerialConnect      (); break;
                case ConnectError:  listener.onSerialConnectError (item.e); break;
                case Read:          listener.onSerialRead         (item.datas); break;
                case IoError:       listener.onSerialIoError      (item.e); break;
            }
        }
        queue1.clear();
        queue2.clear();
    }

    public void detach() {
        if(connected)
            createNotification();
        // items already in event queue (posted before detach() to mainLooper) will end up in queue1
        // items occurring later, will be moved directly to queue2
        // detach() and mainLooper.post run in the main thread, so all items are caught
        listener = null;
    }

    private void createNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel nc = new NotificationChannel(Constants.NOTIFICATION_CHANNEL, "Background service", NotificationManager.IMPORTANCE_LOW);
            nc.setShowBadge(false);
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            nm.createNotificationChannel(nc);
        }
        Intent disconnectIntent = new Intent()
                .setAction(Constants.INTENT_ACTION_DISCONNECT);
        Intent restartIntent = new Intent()
                .setClassName(this, Constants.INTENT_CLASS_MAIN_ACTIVITY)
                .setAction(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER);
        int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0;
        PendingIntent disconnectPendingIntent = PendingIntent.getBroadcast(this, 1, disconnectIntent, flags);
        PendingIntent restartPendingIntent = PendingIntent.getActivity(this, 1, restartIntent,  flags);
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, Constants.NOTIFICATION_CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setColor(getResources().getColor(R.color.colorPrimary))
                .setContentTitle(getResources().getString(R.string.app_name))
                .setContentText(socket != null ? "Connected to "+socket.getName() : "Background Service")
                .setContentIntent(restartPendingIntent)
                .setOngoing(true)
                .addAction(new NotificationCompat.Action(R.drawable.ic_clear_white_24dp, "Disconnect", disconnectPendingIntent));
        // @drawable/ic_notification created with Android Studio -> New -> Image Asset using @color/colorPrimaryDark as background color
        // Android < API 21 does not support vectorDrawables in notifications, so both drawables used here, are created as .png instead of .xml
        Notification notification = builder.build();
        startForeground(Constants.NOTIFY_MANAGER_START_FOREGROUND_SERVICE, notification);
    }

    private void cancelNotification() {
        stopForeground(true);
    }

    /**
     * SerialListener
     */
    public void onSerialConnect() {
        sendTaskerDebugIntent("Connection successful");
        sendTaskerEventIntent("connected");
        retryConnectionStartTime = 0;
        if(connected) {
            synchronized (this) {
                if (listener != null) {
                    mainLooper.post(() -> {
                        if (listener != null) {
                            listener.onSerialConnect();
                        } else {
                            queue1.add(new QueueItem(QueueType.Connect));
                        }
                    });
                } else {
                    queue2.add(new QueueItem(QueueType.Connect));
                }
            }
        }
    }

    public void onSerialConnectError(Exception e) {
        sendTaskerDebugIntent(String.format("SerialConnectError : [%s])", e));
        sendTaskerEventIntent("error");
        boolean stopService = retryConnection();
        if (!stopService && e.getMessage() != null && e.getMessage().toLowerCase().startsWith("gatt status")) {
            return;
        }

        if(connected) {
            synchronized (this) {
                if (listener != null) {
                    mainLooper.post(() -> {
                        if (listener != null) {
                            listener.onSerialConnectError(e);
                        } else {
                            queue1.add(new QueueItem(QueueType.ConnectError, e));
                            disconnect();
                        }
                    });
                } else {
                    queue2.add(new QueueItem(QueueType.ConnectError, e));
                    disconnect();
                }
            }
        }
    }

    public void onSerialRead(ArrayDeque<byte[]> datas) { throw new UnsupportedOperationException(); }

    /**
     * reduce number of UI updates by merging data chunks.
     * Data can arrive at hundred chunks per second, but the UI can only
     * perform a dozen updates if receiveText already contains much text.
     *
     * On new data inform UI thread once (1).
     * While not consumed (2), add more data (3).
     */
    public void onSerialRead(byte[] data) {
        if(connected) {
            Intent intent = new Intent("TASKER_BLE");
            intent.setData(Uri.parse("tasker:" + new String(data)));
            sendBroadcast(intent);
            synchronized (this) {
                if (listener != null) {
                    boolean first;
                    synchronized (lastRead) {
                        first = lastRead.datas.isEmpty(); // (1)
                        lastRead.add(data); // (3)
                    }
                    if(first) {
                        mainLooper.post(() -> {
                            ArrayDeque<byte[]> datas;
                            synchronized (lastRead) {
                                datas = lastRead.datas;
                                lastRead.init(); // (2)
                            }
                            if (listener != null) {
                                listener.onSerialRead(datas);
                            } else {
                                queue1.add(new QueueItem(QueueType.Read, datas));
                            }
                        });
                    }
                } else {
                    if(queue2.isEmpty() || queue2.getLast().type != QueueType.Read)
                        queue2.add(new QueueItem(QueueType.Read));
                    queue2.getLast().add(data);
                }
            }
        }
    }

    public void onSerialIoError(Exception e) {
        sendTaskerDebugIntent(String.format("SerialIoError : [%s])", e));
        sendTaskerEventIntent("error");
        boolean stopService = retryConnection();
        if (!stopService && e.getMessage() != null && e.getMessage().toLowerCase().startsWith("gatt status")) {
            return;
        }

        if(connected) {
            synchronized (this) {
                if (listener != null) {
                    mainLooper.post(() -> {
                        if (listener != null) {
                            listener.onSerialIoError(e);
                        } else {
                            queue1.add(new QueueItem(QueueType.IoError, e));
                            disconnect();
                        }
                    });
                } else {
                    queue2.add(new QueueItem(QueueType.IoError, e));
                    disconnect();
                }
            }
        }
    }

    // Retry connection for a maximum of 5 minutes, with increasing interval between retries
    private boolean retryConnection() {
        if (retryConnectionStartTime == 0) {
            retryConnectionStartTime = Calendar.getInstance().getTimeInMillis();
        } else if ((reconnectTimeout != 0 && Calendar.getInstance().getTimeInMillis() - retryConnectionStartTime > reconnectTimeout) || macAddress == null) {
            return true;
        }
        if (this.socket != null) {
            socket.disconnect();
        }
        sendTaskerDebugIntent("Trying to reconnect");
        connectToMac(macAddress);
        return false;
    }

    // Send string to connected device
    private void sendString(String text) {
        try {
            write(text.getBytes());
        } catch (IOException e) {
            sendTaskerDebugIntent(String.format("Failed to send string [%s]", text));
            onSerialIoError(e);
        }
    }

    private void sendTaskerDebugIntent(String text) {
        Intent intent = new Intent("TASKER_BLE_DEBUG");
        intent.setData(Uri.parse("tasker: [" + Calendar.getInstance().getTime() + "] - " + text));
        sendBroadcast(intent);
    }

    private void sendTaskerEventIntent(String text) {
        Intent intent = new Intent("TASKER_BLE_EVENT");
        intent.setData(Uri.parse("tasker: " + text));
        sendBroadcast(intent);
    }
}
