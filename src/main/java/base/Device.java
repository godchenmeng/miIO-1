package base;

import base.messages.Command;
import base.messages.Response;
import org.json.JSONArray;
import org.json.JSONObject;
import util.ByteArray;

import java.io.IOException;
import java.net.*;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Objects;

@SuppressWarnings("WeakerAccess")
public class Device {

    private static final int PORT = 54321;
    private byte[] rcv = new byte[65507];

    private InetAddress ip;
    private Token token;
    private int retries;

    private DatagramSocket socket;

    private int deviceID = -1;
    private int timeStamp = -1;

    private long methodID;

    public Device(InetAddress ip, Token token, int timeout, int retries) {
        this.ip = ip;
        this.token = token;
        if (timeout < 1) timeout = 1000;
        if (retries < 0) retries = 0;
        this.retries = retries;
        try {
            socket = new DatagramSocket();
            socket.setSoTimeout(timeout);
        } catch (SocketException ignored) {
        }

    }

    private boolean hello(InetAddress broadcast, String[] acceptableModels) {
        if (socket == null) return false;
        Command hello = new Command();
        byte[] helloMsg = hello.create();
        DatagramPacket packet;
        if (ip == null){
            if (acceptableModels == null) return false;
            packet = new DatagramPacket(helloMsg, helloMsg.length, broadcast, PORT);
        } else {
            packet = new DatagramPacket(helloMsg, helloMsg.length, ip, PORT);
        }
        try {
            socket.send(packet);
        } catch (IOException e) {
            return false;
        }
        packet = new DatagramPacket(rcv, rcv.length);
        try {
            socket.receive(packet);
        } catch (IOException e) {
            return false;
        }
        if (ip == null){
            ip = packet.getAddress();
        }
        byte[] worker = new byte[2];
        System.arraycopy(rcv, 2, worker, 0, 2);
        int length = (int)ByteArray.fromBytes(worker);
        worker = new byte[length];
        System.arraycopy(rcv, 0, worker, 0, length);
        Response response = new Response(worker, null);
        if (!response.getToken().equals(new Token("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF",16))){
            token = response.getToken();
        } else if (token == null){
            return false;
        }
        if (!((response.getDeviceID() == -1) || (response.getTimeStamp() == -1))){
            deviceID = response.getDeviceID();
            timeStamp = response.getTimeStamp();
            if (acceptableModels != null){
                boolean modelOk = false;
                for (String s: acceptableModels) {
                    try {
                        if (s.equals(model())) modelOk = true;
                    } catch (CommandExecutionException ignored) {
                    }
                }
                return modelOk;
            }
            return true;
        }
        return false;
    }

    public boolean discover(String[] acceptableModels){
        boolean helloResponse = false;
        for (int helloRetries = this.retries; helloRetries >= 0; helloRetries--) {
            List<InetAddress> broadcast = listAllBroadcastAddresses();
            if (broadcast == null) return false;
            for (InetAddress i : broadcast) {
                if (hello(i, acceptableModels)) {
                    helloResponse = true;
                    break;
                }
            }
            if (helloResponse) break;
        }
        if (helloResponse) {
            methodID = this.timeStamp & 0b1111111111111; // Possible collision about every 2 hours > acceptable
        }
        return helloResponse;
    }

    public Response send(String method, Object params, String[] acceptableModels) throws CommandExecutionException {
        return send(method, params, acceptableModels, this.retries);
    }

    private Response send(String method, Object params, String[] acceptableModels, int sendRetries) throws CommandExecutionException {
        if (deviceID == -1 || timeStamp == -1 || token == null || ip == null) {
            if (!discover(acceptableModels)) throw new CommandExecutionException(CommandExecutionException.Error.DEVICE_NOT_FOUND);
        }
        if (methodID >= 10000) methodID = 1;
        if (ip == null || token == null) throw new CommandExecutionException(CommandExecutionException.Error.IP_OR_TOKEN_UNKNOWN);
        if (socket == null) return null;
        timeStamp++;
        Command msg = new Command(this.token,this.deviceID,timeStamp,this.methodID,method,params);
        methodID++;
        byte[] binMsg = msg.create();

        DatagramPacket packet = new DatagramPacket(binMsg, binMsg.length, ip, PORT);
        try {
            socket.send(packet);
        } catch (SocketTimeoutException to){
            if (sendRetries > 0){
                sendRetries--;
                return send(method, params, acceptableModels, sendRetries);
            }
            throw new CommandExecutionException(CommandExecutionException.Error.TIMEOUT);
        } catch (IOException e) {
            return null;
        }
        packet = new DatagramPacket(rcv, rcv.length);
        try {
            socket.receive(packet);
        } catch (SocketTimeoutException to){
            if (sendRetries > 0){
                sendRetries--;
                return send(method, params, acceptableModels, sendRetries);
            }
            throw new CommandExecutionException(CommandExecutionException.Error.TIMEOUT);
        } catch (IOException e) {
            return null;
        }
        byte[] worker = new byte[2];
        System.arraycopy(rcv, 2, worker, 0, 2);
        int length = (int)ByteArray.fromBytes(worker);
        worker = new byte[length];
        System.arraycopy(rcv, 0, worker, 0, length);
        return parseResponse(worker, method, params, acceptableModels, sendRetries);
    }

    private Response parseResponse(byte[] rawData, String method, Object params, String[] acceptableModels, int sendRetries) throws CommandExecutionException {
        Response response = new Response(rawData, this.token);
        if (!response.isValid()) {
            if (sendRetries > 0){
                sendRetries--;
                return send(method, params, acceptableModels, sendRetries);
            }
            throw new CommandExecutionException(CommandExecutionException.Error.INVALID_RESPONSE);
        }
        if (response.getPayloadID() != (methodID - 1)){
            if (sendRetries > 0){
                sendRetries--;
                return send(method, params, acceptableModels, sendRetries);
            }
            throw new CommandExecutionException(CommandExecutionException.Error.INVALID_RESPONSE);
        }
        if (!((response.getDeviceID() == -1) || (response.getTimeStamp() == -1))){
            if (response.getParams() == null) {
                if (sendRetries > 0){
                    sendRetries--;
                    return send(method, params, acceptableModels, sendRetries);
                }
                throw new CommandExecutionException(CommandExecutionException.Error.EMPTY_RESPONSE);
            }
            if (response.getParams().getClass() == String.class){
                if (response.getParams().equals("unknown_method")) throw new CommandExecutionException((CommandExecutionException.Error.UNKNOWN_METHOD));
            }
            return response;
        }
        throw new CommandExecutionException(CommandExecutionException.Error.INVALID_RESPONSE);
    }

    private List<InetAddress> listAllBroadcastAddresses() {
        List<InetAddress> broadcastList = new ArrayList<>();
        Enumeration<NetworkInterface> interfaces;
        try {
            interfaces = NetworkInterface.getNetworkInterfaces();
        } catch (SocketException e) {
            return null;
        }
        while (interfaces.hasMoreElements()) {
            NetworkInterface networkInterface = interfaces.nextElement();

            try {
                if (networkInterface.isLoopback() || !networkInterface.isUp()) {
                    continue;
                }
            } catch (SocketException e) {
                continue;
            }

            networkInterface.getInterfaceAddresses().stream()
                    .map(InterfaceAddress::getBroadcast)
                    .filter(Objects::nonNull)
                    .forEach(broadcastList::add);
        }
        return broadcastList;
    }

    public JSONObject info() throws CommandExecutionException {
        Response resp = send("miIO.info", null, null);
        if (resp == null) return null;
        if (resp.getParams() == null) return null;
        if (resp.getParams().getClass() != JSONObject.class) return null;
        return (JSONObject)resp.getParams();
    }

    public boolean update(String url, String md5) throws CommandExecutionException {
        if (url == null || md5 == null) return false;
        if (md5.length() != 32) return false;
        JSONObject params = new JSONObject();
        params.put("mode","normal");
        params.put("install", "1");
        params.put("app_url", url);
        params.put("file_md5", md5);
        params.put("proc", "dnld install");
        Response resp = send("miIO.ota", params, null);
        if (resp == null) return false;
        if (resp.getParams() == null) return false;
        if (resp.getParams().getClass() != JSONArray.class) return false;
        return ((JSONArray)resp.getParams()).optString(0).toLowerCase().equals("ok");
    }

    public int updateProgress() throws CommandExecutionException {
        Response resp = send("miIO.get_ota_progress", null, null);
        if (resp == null) return -1;
        if (resp.getParams() == null) return -1;
        if (resp.getParams().getClass() != JSONArray.class) return -1;
        return ((JSONArray)resp.getParams()).optInt(0, -1);
    }

    public String updateStatus() throws CommandExecutionException {
        Response resp = send("miIO.get_ota_state", null, null);
        if (resp == null) return null;
        if (resp.getParams() == null) return null;
        if (resp.getParams().getClass() != JSONArray.class) return null;
        return ((JSONArray)resp.getParams()).optString(0);
    }

    public boolean configureRouter(String ssid, String password) throws CommandExecutionException {
        return configureRouter(ssid, password, 0);
    }

    public boolean configureRouter(String ssid, String password, int uid) throws CommandExecutionException {
        if (ssid == null || password == null) return false;
        JSONObject params = new JSONObject();
        params.put("ssid",ssid);
        params.put("passwd", password);
        params.put("uid", uid);
        Response resp = send("miIO.config_router", params, null);
        if (resp == null) return false;
        if (resp.getParams() == null) return false;
        if (resp.getParams().getClass() != JSONArray.class) return false;
        return ((JSONArray)resp.getParams()).optString(0).toLowerCase().equals("ok");
    }

    public String model() throws CommandExecutionException {
        JSONObject in = info();
        if (in == null) return null;
        return in.optString("model");
    }

    public String firmware() throws CommandExecutionException {
        JSONObject in = info();
        if (in == null) return null;
        return in.optString("fw_ver");
    }
}