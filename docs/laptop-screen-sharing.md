# Laptop screen sharing from oTraiL

The Debian 12 package and Android viewer provide a local, USB-tunneled X11
remote-desktop session. Install `build/debian/otrail-screen_0.4.17_all.deb` on
the Debian laptop, connect the Android phone by USB, enable USB debugging, and
authorize the laptop on the phone.

From a terminal opened in the logged-in graphical desktop:

```sh
otrail-screen windows
otrail-screen start 2
otrail-screen status
otrail-screen switch 1
otrail-screen stop
```

`windows` prints the current `wmctrl` order; `start N` shares that numbered
window. The desktop launcher opens the same listing in a terminal. `switch N`
ends the active stream and starts the selected window. `stop` terminates the
VNC server and removes the ADB reverse port. Run these commands as the desktop
user, not with `sudo`.

In oTraiL, tap the heptagonal desktop icon in the map's top icon row, then tap
**Connect**. The Activity displays the VNC framebuffer; touch and drag send
mouse input, hardware keyboard events are forwarded, and the text field can
send typed text. The viewer connects only to `127.0.0.1:5900`; the host starts
x11vnc with localhost binding and uses `adb reverse` to bridge USB.

## Scope and security

This package currently supports X11 windows. In a Wayland session, `wmctrl` and
x11vnc can access XWayland windows only; native Wayland windows require the
compositor's screen-capture and remote-input permission portals and are not
enumerated by this CLI. The service does not bypass those desktop permissions.

The VNC server uses no VNC password because it is bound to the laptop's loopback
interface and is reachable from the phone only through an already-authorized
ADB connection. Do not remove `-localhost` or expose port 5900 to a network.
The first viewer implementation supports RFB 3.8, None security, raw
framebuffer rectangles, pointer input, and keyboard input; it deliberately
rejects other security modes and rectangle encodings.
