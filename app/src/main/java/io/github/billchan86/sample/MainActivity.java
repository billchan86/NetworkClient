package io.github.billchan86.sample;

import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.v7.app.AppCompatActivity;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;

import java.lang.ref.WeakReference;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;

import io.github.billchan86.lib.network.NetworkClient;
import io.github.billchan86.sample.logger.Log;
import io.github.billchan86.sample.logger.LogFragment;
import io.github.billchan86.sample.logger.LogWrapper;
import io.github.billchan86.sample.logger.MessageOnlyLogFilter;

public class MainActivity extends AppCompatActivity implements View.OnClickListener {
    private static final String LOG_TAG = MainActivity.class.getSimpleName();

    private NetworkClient mClient;

    private EditText mEtIP;
    private EditText mEtPort;
    private EditText mEtInput;
    private Button mBtnConnection;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mEtIP = (EditText) findViewById(R.id.et_ip);
        mEtPort = (EditText) findViewById(R.id.et_port);
        mEtInput = (EditText) findViewById(R.id.et_input);
        mBtnConnection = (Button) findViewById(R.id.btn_connection);
        mBtnConnection.setText("Connect");
        mBtnConnection.setOnClickListener(this);
        findViewById(R.id.btn_send).setOnClickListener(this);
    }

    @Override
    protected  void onStart() {
        super.onStart();
        initializeLogging();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mClient != null) {
            mClient.close();
        }
    }

    @Override
    public void onClick(View v) {
        switch (v.getId()) {
            case R.id.btn_connection:
                if (mClient == null || mClient.getState() == NetworkClient.STATE_NONE) {
                    String ip = mEtIP.getText().toString();
                    if (TextUtils.isEmpty(ip)) {
                        Log.i(LOG_TAG, "IP ERROR!");
                        return;
                    }
                    int port;
                    try {
                        port = Integer.parseInt(mEtPort.getText().toString());
                    } catch (NumberFormatException e) {
                        Log.i(LOG_TAG, "Port ERROR!");
                        return;
                    }
                    mClient = new NetworkClient(new InetSocketAddress(ip, port), mNetworkListener, mNetworkStateHandler);
                    mClient.connect();
                } else {
                    mClient.close();
                }
                break;
            case R.id.btn_send:
                if (mClient == null) {
                    Log.i(LOG_TAG, "Please input ip & port.");
                    return;
                }
                String input = mEtInput.getText().toString();
                if (TextUtils.isEmpty(input)) {
                    Log.i(LOG_TAG, "Please input something.");
                    return;
                }
                ByteBuffer buffer = ByteBuffer.wrap(input.getBytes());
                mClient.send(buffer);
                break;
        }
    }

    private NetworkClient.IClientListener mNetworkListener = new NetworkClient.IClientListener() {
        @Override
        public void onReceived(InetSocketAddress remoteAddress, byte[] data) {
            Log.i(LOG_TAG, "[onReceived] Size:" + data.length + " " + remoteAddress.getAddress().getHostAddress() + ":" + remoteAddress.getPort());
            Log.i(LOG_TAG, new String(data));
        }

        @Override
        public void onSent(InetSocketAddress remoteAddress, int sentCount) {
            Log.i(LOG_TAG, "[onSent] Size:" + sentCount + " " + remoteAddress.getAddress().getHostAddress() + ":" + remoteAddress.getPort());
        }

        @Override
        public void onSendFailed(InetSocketAddress remoteAddress) {
            Log.i(LOG_TAG, "[onSendFailed] " + remoteAddress.getAddress().getHostAddress() + ":" + remoteAddress.getPort());
        }
    };

    private NetworkStateHandler mNetworkStateHandler = new NetworkStateHandler(this);
    private static class NetworkStateHandler extends Handler {
        private final WeakReference<MainActivity> mActivity;

        public NetworkStateHandler(MainActivity activity) {
            mActivity = new WeakReference<>(activity);
        }

        @Override
        public void handleMessage(Message msg) {
            super.handleMessage(msg);
            MainActivity activity = mActivity.get();
            if (activity == null) return;

            switch (msg.what) {
                case NetworkClient.STATE_CONNECTED:
                    Log.i(LOG_TAG, "STATE_CONNECTED");
                    activity.mBtnConnection.setText("Disconnect");
                    break;
                case NetworkClient.STATE_CONNECTING:
                    Log.i(LOG_TAG, "STATE_CONNECTING");
                    break;
                default:
                    Log.i(LOG_TAG, "STATE_NONE");
                    activity.mBtnConnection.setText("Connect");
                    break;
            }

            if (msg.obj instanceof InetSocketAddress) {
                InetSocketAddress remoteAddress = (InetSocketAddress) msg.obj;
                //Log.i(LOG_TAG, "Address: " + remoteAddress.getAddress().getHostAddress() + ":" + remoteAddress.getPort());
            }

        }
    }


    public void initializeLogging() {
        // Wraps Android's native log framework.
        LogWrapper logWrapper = new LogWrapper();
        // Using Log, front-end to the logging chain, emulates android.util.log method signatures.
        Log.setLogNode(logWrapper);

        // Filter strips out everything except the message text.
        MessageOnlyLogFilter msgFilter = new MessageOnlyLogFilter();
        logWrapper.setNext(msgFilter);

        // On screen logging via a fragment with a TextView.
        LogFragment logFragment = (LogFragment) getSupportFragmentManager()
                .findFragmentById(R.id.log_fragment);
        msgFilter.setNext(logFragment.getLogView());
    }
}
