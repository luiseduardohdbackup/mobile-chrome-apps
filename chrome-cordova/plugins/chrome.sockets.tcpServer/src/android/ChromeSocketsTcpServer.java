package org.chromium;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaArgs;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.PluginResult;
import org.apache.cordova.PluginResult.Status;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import android.util.Log;

public class ChromeSocketsTcpServer extends CordovaPlugin {

  private static final String LOG_TAG = "ChromeSocketsTcpServer";

  private Map<Integer, TcpServerSocket> sockets = new ConcurrentHashMap<Integer, TcpServerSocket>();
  private BlockingQueue<SelectorMessage> selectorMessages =
      new LinkedBlockingQueue<SelectorMessage>();
  private int nextSocket = 0;
  private CallbackContext acceptContext;
  private Selector selector;
  private SelectorThread selectorThread;

  @Override
  public boolean execute(String action, CordovaArgs args, final CallbackContext callbackContext)
      throws JSONException {
    if ("create".equals(action)) {
      create(args, callbackContext);
    } else if ("update".equals(action)) {
      update(args, callbackContext);
    } else if ("setPaused".equals(action)) {
      setPaused(args, callbackContext);
    } else if ("listen".equals(action)) {
      listen(args, callbackContext);
    } else if ("disconnect".equals(action)) {
      disconnect(args, callbackContext);
    } else if ("close".equals(action)) {
      close(args, callbackContext);
    } else if ("getInfo".equals(action)) {
      getInfo(args, callbackContext);
    } else if ("getSockets".equals(action)) {
      getSockets(args, callbackContext);
    } else if ("registerAcceptEvents".equals(action)) {
      registerAcceptEvents(args, callbackContext);
    } else {
      return false;
    }
    return true;
  }

  public void onDestory() {
    closeAllSockets();
    stopSelectorThread();
  }

  public void onRest() {
    closeAllSockets();
    stopSelectorThread();
  }

  private JSONObject buildErrorInfo(int code, String message)
      throws JSONException {
    JSONObject error = new JSONObject();
    error.put("message", message);
    error.put("resultCode", code);
    return error;
  }

  private void create(CordovaArgs args, final CallbackContext callbackContext)
      throws JSONException {
    JSONObject properties = args.getJSONObject(0);

    try {
      TcpServerSocket socket = new TcpServerSocket(nextSocket++, acceptContext, properties);
      sockets.put(Integer.valueOf(socket.getSocketId()), socket);
      callbackContext.success(socket.getSocketId());
    } catch (IOException e) {
    }
  }

  private void update(CordovaArgs args, final CallbackContext callbackContext)
      throws JSONException {
    int socketId = args.getInt(0);
    JSONObject properties = args.getJSONObject(1);


    TcpServerSocket socket = sockets.get(Integer.valueOf(socketId));

    if (socket == null) {
      Log.e(LOG_TAG, "No socket with socketId " + socketId);
      return;
    }

    try {
      socket.setProperties(properties);
      callbackContext.success();
    } catch (SocketException e) {
    }
  }

  private void setPaused(CordovaArgs args, final CallbackContext callbackContext)
      throws JSONException {
    int socketId = args.getInt(0);
    boolean paused = args.getBoolean(1);

    TcpServerSocket socket = sockets.get(Integer.valueOf(socketId));

    if (socket == null) {
      Log.e(LOG_TAG, "No socket with socketId " + socketId);
      return;
    }

    socket.setPaused(paused);

    if (paused) {
      callbackContext.success();
    } else {
      // All interests need to be modified in selector thread.
      addSelectorMessage(socket, SelectorMessageType.SO_ADD_ACCEPT_INTEREST, callbackContext);
    }
  }

  private void listen(CordovaArgs args, final CallbackContext callbackContext)
      throws JSONException {

    int socketId = args.getInt(0);
    String address = args.getString(1);
    int port = args.getInt(2);

    TcpServerSocket socket = sockets.get(Integer.valueOf(socketId));

    if (socket == null) {
      Log.e(LOG_TAG, "No socket with socketId " + socketId);
      callbackContext.error(buildErrorInfo(-4, "Invalid Argument"));
      return;
    }

    try {
      if (args.isNull(3)) {
        socket.listen(address, port);
      } else {
        int backlog = args.getInt(3);
        socket.listen(address, port, backlog);
      }
      addSelectorMessage(socket, SelectorMessageType.SO_LISTEN, null);
      callbackContext.success();
    } catch (IOException e) {
      callbackContext.error(buildErrorInfo(-2, e.getMessage()));
    }
  }

  private void disconnect(CordovaArgs args, final CallbackContext callbackContext)
      throws JSONException {
    int socketId = args.getInt(0);

    TcpServerSocket socket = sockets.get(Integer.valueOf(socketId));

    if (socket == null) {
      Log.e(LOG_TAG, "No socket with socketId " + socketId);
      return;
    }

    addSelectorMessage(socket, SelectorMessageType.SO_DISCONNECTED, callbackContext);
  }

  private void closeAllSockets() {
    for(TcpServerSocket socket: sockets.values()) {
      addSelectorMessage(socket, SelectorMessageType.SO_CLOSE, null);
    }
  }

  private void close(CordovaArgs args, final CallbackContext callbackContext)
      throws JSONException {
    int socketId = args.getInt(0);

    TcpServerSocket socket = sockets.get(Integer.valueOf(socketId));

    if (socket == null) {
      Log.e(LOG_TAG, "No socket with socketId " + socketId);
      return;
    }

    addSelectorMessage(socket, SelectorMessageType.SO_CLOSE, callbackContext);
  }

  private void getInfo(CordovaArgs args, final CallbackContext callbackContext)
      throws JSONException {
    int socketId = args.getInt(0);

    TcpServerSocket socket = sockets.get(Integer.valueOf(socketId));

    if (socket == null) {
      Log.e(LOG_TAG, "No socket with socketId " + socketId);
      return;
    }
    callbackContext.success(socket.getInfo());
  }

  private void getSockets(CordovaArgs args, final CallbackContext callbackContext)
      throws JSONException {

    JSONArray results = new JSONArray();

    for (TcpServerSocket socket: sockets.values()) {
      results.put(socket.getInfo());
    }

    callbackContext.success(results);
  }

  private void registerAcceptEvents(CordovaArgs args, final CallbackContext callbackContext)
      throws JSONException {
    acceptContext = callbackContext;
    startSelectorThread();
  }

  private void startSelectorThread() throws JSONException {
    if (selector != null && selectorThread != null) return;
    try {
      selector = Selector.open();
      selectorThread = new SelectorThread(selector, selectorMessages, sockets);
      selectorThread.start();
    } catch (IOException e) {
      selector = null;
      selectorThread = null;
      PluginResult err = new PluginResult(Status.ERROR, buildErrorInfo(-9, e.getMessage()));
      err.setKeepCallback(true);
      acceptContext.sendPluginResult(err);
    }
  }

  private void stopSelectorThread() {
    if (selector == null && selectorThread == null) return;

    addSelectorMessage(null, SelectorMessageType.T_STOP, null);
    try {
      selectorThread.join();
      selector = null;
      selectorThread = null;
    } catch (InterruptedException e) {
    }
  }

  private void addSelectorMessage(
      TcpServerSocket socket, SelectorMessageType type, CallbackContext callbackContext) {
    try {
      selectorMessages.put(new SelectorMessage(
          socket, type, callbackContext));
      if (selector != null)
        selector.wakeup();
    } catch (InterruptedException e) {
    }
  }

  private enum SelectorMessageType {
    SO_LISTEN,
    SO_DISCONNECTED,
    SO_CLOSE,
    SO_ADD_ACCEPT_INTEREST,
    T_STOP;
  }

  private class SelectorMessage {
    final TcpServerSocket socket;
    final SelectorMessageType type;
    final CallbackContext callbackContext;

    SelectorMessage(
        TcpServerSocket socket, SelectorMessageType type, CallbackContext callbackContext) {
      this.socket = socket;
      this.type = type;
      this.callbackContext = callbackContext;
    }
  }

  private class SelectorThread extends Thread {
    private final Selector selector;
    private BlockingQueue<SelectorMessage> selectorMessages;
    private Map<Integer, TcpServerSocket> sockets;
    private boolean running = true;

    SelectorThread(
        Selector selector, BlockingQueue<SelectorMessage> selectorMessages,
        Map<Integer, TcpServerSocket> sockets) {
      this.selector = selector;
      this.selectorMessages = selectorMessages;
      this.sockets = sockets;
    }

    private void processPendingMessages() throws JSONException {

      while (selectorMessages.peek() != null) {
        SelectorMessage msg = null;
        try {
          msg = selectorMessages.take();
          switch (msg.type) {
            case SO_LISTEN:
              msg.socket.register(selector, SelectionKey.OP_ACCEPT);
              break;
            case SO_DISCONNECTED:
              msg.socket.disconnect();
              break;
            case SO_CLOSE:
              msg.socket.disconnect();
              sockets.remove(Integer.valueOf(msg.socket.getSocketId()));
              break;
            case SO_ADD_ACCEPT_INTEREST:
              msg.socket.addInterestSet(SelectionKey.OP_ACCEPT);
             break;
            case T_STOP:
              running = false;
              break;
          }
          if (msg.callbackContext != null)
            msg.callbackContext.success();
        } catch (InterruptedException e) {
        } catch (IOException e) {
          if (msg.callbackContext != null)
            msg.callbackContext.error(buildErrorInfo(-2, e.getMessage()));
        }
      }
    }

    public void run() {
      Iterator<SelectionKey> it;
      while (running) {
        try {
          selector.select();
        } catch (IOException e) {
          continue;
        }

        it = selector.selectedKeys().iterator();

        try {
          while (it.hasNext()) {

            SelectionKey key = it.next();
            it.remove();

            if (!key.isValid()) {
              continue;
            }

            TcpServerSocket socket = (TcpServerSocket)key.attachment();

            if (key.isAcceptable()) {
              socket.accept();
            }
          }

          processPendingMessages();
        } catch (JSONException e) {
        }
      }
    }
  }

  private class TcpServerSocket {
    private final int socketId;
    private final CallbackContext acceptContext;

    private ServerSocketChannel channel;

    private SelectionKey key;

    private boolean paused;

    private boolean persistent;
    private String name;

    TcpServerSocket(int socketId, CallbackContext acceptContext, JSONObject properties)
        throws JSONException, IOException {
      this.socketId = socketId;
      this.acceptContext = acceptContext;

      channel = ServerSocketChannel.open();
      channel.configureBlocking(false);

      // set socket default options
      paused = false;
      persistent = false;
      name = "";

      setProperties(properties);
    }

    // Only call this method on selector thread
    void addInterestSet(int interestSet) {
      if (key != null) {
        key.interestOps(key.interestOps() | interestSet);
        key.selector().wakeup();
      }
    }

    // Only call this method on selector thread
    void removeInterestSet(int interestSet) {
      if (key != null) {
        key.interestOps(key.interestOps() & ~interestSet);
        key.selector().wakeup();
      }
    }

    int getSocketId() {
      return socketId;
    }

    void register(Selector selector, int interestSets) throws IOException {
      key = channel.register(selector, interestSets, this);
    }

    void setProperties(JSONObject properties) throws JSONException, SocketException {

      if (!properties.isNull("persistent"))
        persistent = properties.getBoolean("persistent");

      if (!properties.isNull("name"))
        name = properties.getString("name");
    }

    void setPaused(boolean paused) {
      this.paused = paused;
    }

    void setUpListen() throws IOException {
      if (!channel.isOpen()) {
        channel = ServerSocketChannel.open();
        channel.configureBlocking(false);
      }
    }

    void listen(String address, int port) throws IOException {
      setUpListen();
      channel.socket().bind(new InetSocketAddress(port));
    }

    void listen(String address, int port, int backlog) throws IOException {
      setUpListen();
      channel.socket().bind(new InetSocketAddress(port), backlog);
    }

    void disconnect() throws IOException {
      if (key != null && channel.isRegistered())
        key.cancel();
      channel.close();
    }

    JSONObject getInfo() throws JSONException {

      JSONObject info = new JSONObject();

      info.put("socketId", socketId);
      info.put("persistent", persistent);
      info.put("name", name);
      info.put("paused", paused);

      if (channel.socket().getInetAddress() != null) {
        info.put("localAddress", channel.socket().getInetAddress().getHostAddress());
        info.put("localPort", channel.socket().getLocalPort());
      }

      return info;
    }

    // This method can be only called by selector thread.
    void accept() throws JSONException {

      if (paused) {
        // Remove accept interests to avoid seletor wakeup when acceptable.
        removeInterestSet(SelectionKey.OP_ACCEPT);
        return;
      }

      try {
        SocketChannel acceptedSocket = channel.accept();
        ChromeSocketsTcp tcpPlugin =
            (ChromeSocketsTcp) webView.getPluginManager().getPlugin("ChromeSocketsTcp");
        int clientSocketId = tcpPlugin.registerAcceptedSocketChannel(acceptedSocket);

        JSONObject info = new JSONObject();
        info.put("socketId", socketId);
        info.put("clientSocketId", clientSocketId);

        PluginResult acceptedResult = new PluginResult(Status.OK, info);
        acceptedResult.setKeepCallback(true);

        acceptContext.sendPluginResult(acceptedResult);

      } catch (IOException e) {
        JSONObject info = buildErrorInfo(-2, e.getMessage());
        info.put("socketId", socketId);
        PluginResult errResult = new PluginResult(Status.ERROR, info);
        errResult.setKeepCallback(true);
        acceptContext.sendPluginResult(errResult);
      }
    }
  }
}
