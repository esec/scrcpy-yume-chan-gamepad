package com.genymobile.scrcpy;

import android.graphics.Rect;
import android.media.MediaCodecInfo;
import android.os.BatteryManager;
import android.os.Build;
import android.text.TextUtils;

import java.io.InputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Locale;

public final class Server {

    public static final String SERVER_DIR = "/data/local/tmp";
    public static final String[] NATIVE_LIBRARIES = {
        "libjnidispatch.so",
    };

    private Server() {
        // not instantiable
    }

    private static void initAndCleanUp(Options options) {
        boolean mustDisableShowTouchesOnCleanUp = false;
        int restoreStayOn = -1;
        boolean restoreNormalPowerMode = options.getControl(); // only restore power mode if control is enabled
        if (options.getShowTouches() || options.getStayAwake()) {
            if (options.getShowTouches()) {
                try {
                    String oldValue = Settings.getAndPutValue(Settings.TABLE_SYSTEM, "show_touches", "1");
                    // If "show touches" was disabled, it must be disabled back on clean up
                    mustDisableShowTouchesOnCleanUp = !"1".equals(oldValue);
                } catch (SettingsException e) {
                    Ln.e("Could not change \"show_touches\"", e);
                }
            }

            if (options.getStayAwake()) {
                int stayOn = BatteryManager.BATTERY_PLUGGED_AC | BatteryManager.BATTERY_PLUGGED_USB | BatteryManager.BATTERY_PLUGGED_WIRELESS;
                try {
                    String oldValue = Settings.getAndPutValue(Settings.TABLE_GLOBAL, "stay_on_while_plugged_in", String.valueOf(stayOn));
                    try {
                        restoreStayOn = Integer.parseInt(oldValue);
                        if (restoreStayOn == stayOn) {
                            // No need to restore
                            restoreStayOn = -1;
                        }
                    } catch (NumberFormatException e) {
                        restoreStayOn = 0;
                    }
                } catch (SettingsException e) {
                    Ln.e("Could not change \"stay_on_while_plugged_in\"", e);
                }
            }
        }

        if (options.getCleanup()) {
            try {
                CleanUp.configure(options.getDisplayId(), restoreStayOn, mustDisableShowTouchesOnCleanUp, restoreNormalPowerMode,
                        options.getPowerOffScreenOnClose());
            } catch (IOException e) {
                Ln.e("Could not configure cleanup", e);
            }
        }
    }

    private static void scrcpy(Options options) throws IOException {
        Ln.i("Device: " + Build.MANUFACTURER + " " + Build.MODEL + " (Android " + Build.VERSION.RELEASE + ")");
        Ln.i("Supported ABIs: " + TextUtils.join(", ", Build.SUPPORTED_ABIS));
        final Device device = new Device(options);
        List<CodecOption> codecOptions = options.getCodecOptions();

        for (String lib : NATIVE_LIBRARIES) {
            for (String abi : Build.SUPPORTED_ABIS) {
                try {
                    InputStream resStream = Server.class.getResourceAsStream("/lib/" + abi + "/" + lib);
                    FileOutputStream fileStream = new FileOutputStream(SERVER_DIR + "/" + lib);

                    byte[] buffer = new byte[1024];
                    int length;
                    while ((length = resStream.read(buffer)) > 0) {
                        fileStream.write(buffer, 0, length);
                    }

                    resStream.close();
                    fileStream.close();

                    break;
                } catch (Exception e) {
                    Ln.e("Could not extract native library for " + abi, e);
                }
            }
        }

        Thread initThread = startInitThread(options);

        int uid = options.getUid();
        boolean tunnelForward = options.isTunnelForward();
        boolean control = options.getControl();
        boolean sendDummyByte = options.getSendDummyByte();

        Workarounds.prepareMainLooper();
        if (Build.BRAND.equalsIgnoreCase("meizu")) {
            // Workarounds must be applied for Meizu phones:
            //  - <https://github.com/Genymobile/scrcpy/issues/240>
            //  - <https://github.com/Genymobile/scrcpy/issues/365>
            //  - <https://github.com/Genymobile/scrcpy/issues/2656>
            //
            // But only apply when strictly necessary, since workarounds can cause other issues:
            //  - <https://github.com/Genymobile/scrcpy/issues/940>
            //  - <https://github.com/Genymobile/scrcpy/issues/994>
            Workarounds.fillAppInfo();
        }

        try (DesktopConnection connection = DesktopConnection.open(uid, tunnelForward, control, sendDummyByte)) {
            VideoCodec codec = options.getCodec();
            if (options.getSendDeviceMeta()) {
                Size videoSize = device.getScreenInfo().getVideoSize();
                connection.sendDeviceMeta(Device.getDeviceName(), videoSize.getWidth(), videoSize.getHeight());
            }
            ScreenEncoder screenEncoder = new ScreenEncoder(codec.getMimeType(), options.getBitRate(), options.getMaxFps(), codecOptions,
                    options.getEncoderName(), options.getDownsizeOnError());

            Controller controller = null;
            if (control) {
                controller = new Controller(device, connection, options.getClipboardAutosync(), options.getPowerOn());
                controller.start();

                final Controller controllerRef = controller;
                device.setClipboardListener(text -> controllerRef.getSender().pushClipboardText(text));
            }

            try {
                // synchronous
                VideoStreamer videoStreamer = new VideoStreamer(connection.getVideoFd(), options.getSendFrameMeta());
                if (options.getSendCodecId()) {
                    videoStreamer.writeHeader(codec.getId());
                }
                screenEncoder.streamScreen(device, videoStreamer);
            } catch (IOException e) {
                // this is expected on close
                Ln.d("Screen streaming stopped");
            } finally {
                initThread.interrupt();
                if (controller != null) {
                    controller.stop();
                }
            }
        }
    }

    private static Thread startInitThread(final Options options) {
        Thread thread = new Thread(() -> initAndCleanUp(options));
        thread.start();
        return thread;
    }

    private static Options createOptions(String... args) {
        if (args.length < 1) {
            throw new IllegalArgumentException("Missing client version");
        }

        String clientVersion = args[0];
        if (!clientVersion.equals(BuildConfig.VERSION_NAME)) {
            throw new IllegalArgumentException(
                    "The server version (" + BuildConfig.VERSION_NAME + ") does not match the client " + "(" + clientVersion + ")");
        }

        Options options = new Options();

        for (int i = 1; i < args.length; ++i) {
            String arg = args[i];
            int equalIndex = arg.indexOf('=');
            if (equalIndex == -1) {
                throw new IllegalArgumentException("Invalid key=value pair: \"" + arg + "\"");
            }
            String key = arg.substring(0, equalIndex);
            String value = arg.substring(equalIndex + 1);
            switch (key) {
                case "uid":
                    int uid = Integer.parseInt(value, 0x10);
                    if (uid < -1) {
                        throw new IllegalArgumentException("uid may not be negative (except -1 for 'none'): " + uid);
                    }
                    options.setUid(uid);
                    break;
                case "log_level":
                    Ln.Level level = Ln.Level.valueOf(value.toUpperCase(Locale.ENGLISH));
                    options.setLogLevel(level);
                    break;
                case "codec":
                    VideoCodec codec = VideoCodec.findByName(value);
                    if (codec == null) {
                        throw new IllegalArgumentException("Video codec " + value + " not supported");
                    }
                    options.setCodec(codec);
                    break;
                case "max_size":
                    int maxSize = Integer.parseInt(value) & ~7; // multiple of 8
                    options.setMaxSize(maxSize);
                    break;
                case "bit_rate":
                    int bitRate = Integer.parseInt(value);
                    options.setBitRate(bitRate);
                    break;
                case "max_fps":
                    int maxFps = Integer.parseInt(value);
                    options.setMaxFps(maxFps);
                    break;
                case "lock_video_orientation":
                    int lockVideoOrientation = Integer.parseInt(value);
                    options.setLockVideoOrientation(lockVideoOrientation);
                    break;
                case "tunnel_forward":
                    boolean tunnelForward = Boolean.parseBoolean(value);
                    options.setTunnelForward(tunnelForward);
                    break;
                case "crop":
                    Rect crop = parseCrop(value);
                    options.setCrop(crop);
                    break;
                case "control":
                    boolean control = Boolean.parseBoolean(value);
                    options.setControl(control);
                    break;
                case "display_id":
                    int displayId = Integer.parseInt(value);
                    options.setDisplayId(displayId);
                    break;
                case "show_touches":
                    boolean showTouches = Boolean.parseBoolean(value);
                    options.setShowTouches(showTouches);
                    break;
                case "stay_awake":
                    boolean stayAwake = Boolean.parseBoolean(value);
                    options.setStayAwake(stayAwake);
                    break;
                case "codec_options":
                    List<CodecOption> codecOptions = CodecOption.parse(value);
                    options.setCodecOptions(codecOptions);
                    break;
                case "encoder_name":
                    if (!value.isEmpty()) {
                        options.setEncoderName(value);
                    }
                    break;
                case "power_off_on_close":
                    boolean powerOffScreenOnClose = Boolean.parseBoolean(value);
                    options.setPowerOffScreenOnClose(powerOffScreenOnClose);
                    break;
                case "clipboard_autosync":
                    boolean clipboardAutosync = Boolean.parseBoolean(value);
                    options.setClipboardAutosync(clipboardAutosync);
                    break;
                case "downsize_on_error":
                    boolean downsizeOnError = Boolean.parseBoolean(value);
                    options.setDownsizeOnError(downsizeOnError);
                    break;
                case "cleanup":
                    boolean cleanup = Boolean.parseBoolean(value);
                    options.setCleanup(cleanup);
                    break;
                case "power_on":
                    boolean powerOn = Boolean.parseBoolean(value);
                    options.setPowerOn(powerOn);
                    break;
                case "send_device_meta":
                    boolean sendDeviceMeta = Boolean.parseBoolean(value);
                    options.setSendDeviceMeta(sendDeviceMeta);
                    break;
                case "send_frame_meta":
                    boolean sendFrameMeta = Boolean.parseBoolean(value);
                    options.setSendFrameMeta(sendFrameMeta);
                    break;
                case "send_dummy_byte":
                    boolean sendDummyByte = Boolean.parseBoolean(value);
                    options.setSendDummyByte(sendDummyByte);
                    break;
                case "send_codec_id":
                    boolean sendCodecId = Boolean.parseBoolean(value);
                    options.setSendCodecId(sendCodecId);
                    break;
                case "raw_video_stream":
                    boolean rawVideoStream = Boolean.parseBoolean(value);
                    if (rawVideoStream) {
                        options.setSendDeviceMeta(false);
                        options.setSendFrameMeta(false);
                        options.setSendDummyByte(false);
                        options.setSendCodecId(false);
                    }
                    break;
                default:
                    Ln.w("Unknown server option: " + key);
                    break;
            }
        }

        return options;
    }

    private static Rect parseCrop(String crop) {
        if (crop.isEmpty()) {
            return null;
        }
        // input format: "width:height:x:y"
        String[] tokens = crop.split(":");
        if (tokens.length != 4) {
            throw new IllegalArgumentException("Crop must contains 4 values separated by colons: \"" + crop + "\"");
        }
        int width = Integer.parseInt(tokens[0]);
        int height = Integer.parseInt(tokens[1]);
        int x = Integer.parseInt(tokens[2]);
        int y = Integer.parseInt(tokens[3]);
        return new Rect(x, y, x + width, y + height);
    }

    private static void suggestFix(Throwable e) {
        if (e instanceof InvalidDisplayIdException) {
            InvalidDisplayIdException idie = (InvalidDisplayIdException) e;
            int[] displayIds = idie.getAvailableDisplayIds();
            if (displayIds != null && displayIds.length > 0) {
                Ln.e("Try to use one of the available display ids:");
                for (int id : displayIds) {
                    Ln.e("    scrcpy --display " + id);
                }
            }
        } else if (e instanceof InvalidEncoderException) {
            InvalidEncoderException iee = (InvalidEncoderException) e;
            MediaCodecInfo[] encoders = iee.getAvailableEncoders();
            if (encoders != null && encoders.length > 0) {
                Ln.e("Try to use one of the available encoders:");
                for (MediaCodecInfo encoder : encoders) {
                    Ln.e("    scrcpy --encoder '" + encoder.getName() + "'");
                }
            }
        }
    }

    public static void main(String... args) throws Exception {
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            Ln.e("Exception on thread " + t, e);
            suggestFix(e);
        });

        Options options = createOptions(args);

        Ln.initLogLevel(options.getLogLevel());

        scrcpy(options);
    }
}
