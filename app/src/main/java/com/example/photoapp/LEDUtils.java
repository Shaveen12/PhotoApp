package com.example.photoapp;

import java.io.FileWriter;
import java.io.IOException;

public class LEDUtils {

    public static final int RED = 0;
    public static final int GREEN = 1;
    public static final int BLUE = 2;

    public static void setled(int color, boolean onoff) {

        String leddev = "/sys/class/leds/red/brightness";
        String BLUE_LED_DEV = "/sys/class/leds/blue/brightness";
        String GREEN_LED_DEV = "/sys/class/leds/green/brightness";

        if (color == GREEN)
            leddev = GREEN_LED_DEV;
        else if (color == BLUE)
            leddev = BLUE_LED_DEV;

        writeFile(leddev, onoff ? "255" : "0");
    }

    public static void setled(int color, int ontime, int offtime, boolean onoff) {

        String ledtri = "/sys/class/leds/red/trigger";
        String ledontime = "/sys/class/leds/red/delay_on";
        String ledofftime = "/sys/class/leds/red/delay_off";

        if (color == GREEN) {
            ledtri = "/sys/class/leds/green/trigger";
            ledontime = "/sys/class/leds/green/delay_on";
            ledofftime = "/sys/class/leds/green/delay_off";
        } else if (color == BLUE) {
            ledtri = "/sys/class/leds/blue/trigger";
            ledontime = "/sys/class/leds/blue/delay_on";
            ledofftime = "/sys/class/leds/blue/delay_off";
        }

        if (onoff == false) {
            writeFile(ledtri, "timer");
            writeFile(ledontime, "0");
            return;
        }

        writeFile(ledtri, "timer");
        writeFile(ledontime, String.valueOf(ontime));
        writeFile(ledofftime, String.valueOf(offtime));
    }

    private static void writeFile(String path, String content) {
        FileWriter fw = null;
        try {
            fw = new FileWriter(path);
            fw.write(content);
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            if (fw != null)
                try {
                    fw.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
        }
    }
}