package io.github.billchan86.lib.network;

import android.os.Handler;
import android.support.annotation.Nullable;
import android.util.Log;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.concurrent.LinkedBlockingQueue;

public class NetworkClient {
    // Debugging
    private static final String TAG = "NetworkClient";
    private static final boolean D = true;

    public interface IClientListener {
        // 接收数据后调用此接口
        void onReceived(InetSocketAddress remoteAddress, byte[] data);

        // 数据发送后调用此接口
        void onSent(InetSocketAddress remoteAddress, int sentCount);

        // 数据发送失败后调用此接口
        void onSendFailed(InetSocketAddress remoteAddress);
    }

    public static final int STATE_NONE = 0;        //未连接
    public static final int STATE_CONNECTING = 1;  //正在链接
    public static final int STATE_CONNECTED = 2;   //已连接
    private volatile int mState = STATE_NONE;
    private Handler mStateHandler;

    private InetSocketAddress mRemoteAddress;
    private IClientListener mClientListener;

    private Thread mClientThread = null;
    private Selector mSelector;

    // 发送队列
    private LinkedBlockingQueue<ByteBuffer> mSendQueue = new LinkedBlockingQueue<>();
    // 接受缓冲
    private ByteBuffer mReceiveBuffer = ByteBuffer.allocate(2048);

    public NetworkClient(InetSocketAddress remoteAddress, @Nullable IClientListener clientListener, @Nullable Handler stateHandler) {
        mRemoteAddress = remoteAddress;
        mClientListener = clientListener;
        mStateHandler = stateHandler;
        mState = STATE_NONE;
    }

    public InetSocketAddress getRemoteAddress() { return mRemoteAddress; }

    private void setState(int state) {
        mState = state;
        if (D) {
            switch (mState) {
                case STATE_CONNECTING:
                    Log.d(TAG, "connecting");
                    break;
                case STATE_CONNECTED:
                    Log.d(TAG, "connected");
                    break;
                default:
                    Log.d(TAG, "not connected");
                    break;
            }
        }
        if (mStateHandler != null) {
            mStateHandler.obtainMessage(mState, 0, 0, mRemoteAddress).sendToTarget();
        }
    }

    public int getState() { return mState; }

    public void connect() {
        if (mState == STATE_NONE) {
            //TODO 检查网络状态

            mClientThread = new ClientThread();
            mClientThread.start();
        }
    }

    public void send(ByteBuffer buffer) {
        mSendQueue.offer(buffer);

        //检查是否连接
        connect();

        //已连接状态则唤醒Selector进行发讯，其他状态只加入发讯队列即可
        if (mState == STATE_CONNECTED) {
            mSelector.wakeup();
        }
    }

    public void close() {
        if (mState == STATE_NONE) return;

        // 停止线程运行方式一：使用interrupt()
        if (mClientThread != null && mClientThread.isAlive()) {
            mClientThread.interrupt();
        }
        mClientThread = null;
        // 停止线程运行方式二：设置volatile条件变量
        mState = STATE_NONE;
    }

    public class ClientThread extends Thread {
        @Override
        public void run() {
            if (D) Log.d(TAG, String.format("Thread[%d] start RUN.", Thread.currentThread().getId()));
            SocketChannel socketChannel = null;
            try {
                setState(STATE_CONNECTING);
                socketChannel = SocketChannel.open();
                socketChannel.configureBlocking(false);
                mSelector = Selector.open();
                socketChannel.register(mSelector, SelectionKey.OP_CONNECT);
                if (D) Log.i(TAG, "Connect to " + mRemoteAddress.toString());
                socketChannel.connect(mRemoteAddress);

                while (mState != STATE_NONE) {
                    mSelector.select();

                    //当调用Thread.interrupt()进行中断线程时，上面的Selector的阻塞操作会马上返回，在此处立马检查线程状态
                    if (Thread.interrupted()) {
                        throw new InterruptedException(String.format("Thread[%d] has been Interrupted.",
                                Thread.currentThread().getId()));
                    }

                    Iterator<SelectionKey> it = mSelector.selectedKeys().iterator();
                    while (it.hasNext()) {
                        SelectionKey key = it.next();
                        it.remove();

                        if (!key.isValid()) continue;

                        if (key.isConnectable()) {
                            connect(key);
                        } else if (key.isReadable()) {
                            read(key);
                        } else if (key.isWritable()) {
                            write(key);
                        }
                    }

                    if (!mSendQueue.isEmpty()) {
                        SelectionKey key = socketChannel.keyFor(mSelector);
                        key.interestOps(SelectionKey.OP_WRITE);
                    }
                }
            } catch (IOException e) {
                if (D) Log.e(TAG, "IOException occur. " + e.getMessage());
            } catch (InterruptedException e) {
                if (D) Log.e(TAG, "InterruptedException occur. " + e.getMessage());
            } finally {
                if (socketChannel != null) {
                    SelectionKey key = socketChannel.keyFor(mSelector);
                    key.cancel();
                    try {
                        socketChannel.close();  //关闭Socket
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
                mSendQueue.clear();
                setState(STATE_NONE);
                if (D) Log.d(TAG, String.format("Thread[%d] END", Thread.currentThread().getId()));
            }
        }

        private void connect(SelectionKey key) throws IOException {
            SocketChannel socketChannel = (SocketChannel) key.channel();
            if (socketChannel.isConnectionPending()) {
                try {
                    if (socketChannel.finishConnect()) {
                        key.interestOps(SelectionKey.OP_READ);
                        setState(STATE_CONNECTED);
                    }
                } catch (IOException e) {
                    if (mClientListener != null) {
                        mClientListener.onSendFailed(mRemoteAddress);
                    }
                    throw e;
                }
            }
        }

        private void read(SelectionKey key) throws IOException {
            SocketChannel socketChannel = (SocketChannel) key.channel();
            mReceiveBuffer.clear();
            int count = socketChannel.read(mReceiveBuffer);
            if (count <= 0) {
                throw new IOException(String.format("Thread[%d] read error:%d", Thread.currentThread().getId(), count));
            } else {
                byte[] data = new byte[count];
                System.arraycopy(mReceiveBuffer.array(), 0, data, 0, count);

                if (D) {
                    Log.i(TAG, "From: " + mRemoteAddress.getAddress().getHostAddress() + ":" +
                            mRemoteAddress.getPort() + ", Received " + count + " bytes.");
                    StringBuilder log = new StringBuilder();
                    for (int i = 0; i < count; i++) {
                        StringBuilder builder = new StringBuilder();
                        String hex = Integer.toHexString(data[i]&0xFF).toUpperCase();
                        if (hex.length() == 1) {
                            builder.append("0");
                        }
                        builder.append(hex);
                        log.append(builder).append(" ");
                    }
                    Log.v(TAG, log.toString());
                }

                if (mClientListener != null) {
                    mClientListener.onReceived(mRemoteAddress, data);
                }
            }
        }

        private void write(SelectionKey key) throws IOException {
            SocketChannel socketChannel = (SocketChannel) key.channel();

            while (!mSendQueue.isEmpty()) {
                ByteBuffer buffer = mSendQueue.poll();
                int count;
                try {
                    count = socketChannel.write(buffer);
                } catch (IOException e) {
                    if (mClientListener != null) {
                        mClientListener.onSendFailed(mRemoteAddress);
                    }
                    throw e;
                }

                if (D) {
                    Log.i(TAG, "To: " + mRemoteAddress.getAddress().getHostAddress() + ":" +
                            mRemoteAddress.getPort() + ", Sent " + count + " bytes.");
                    StringBuilder log = new StringBuilder();
                    byte[] b = buffer.array();
                    for (int i = 0; i < count; ++i) {
                        StringBuilder builder = new StringBuilder();
                        String hex = Integer.toHexString(b[i]&0xFF).toUpperCase();
                        if (hex.length() == 1) {
                            builder.append("0");
                        }
                        builder.append(hex);
                        log.append(builder).append(" ");
                    }
                    Log.v(TAG, log.toString());
                }

                if (mClientListener != null) {
                    mClientListener.onSent(mRemoteAddress, count);
                }
            }

            key.interestOps(SelectionKey.OP_READ);
        }
    }
}
