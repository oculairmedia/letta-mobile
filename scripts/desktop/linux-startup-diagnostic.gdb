# Trace the packaged AppRun, following its exec into the real jpackage launcher.
# Pending breakpoints resolve when Xlib and the packaged libawt_xawt are loaded.
set pagination off
set confirm off
set breakpoint pending on
set startup-with-shell off
set disable-randomization off
set follow-fork-mode parent
set follow-exec-mode same
set detach-on-fork on
# HotSpot uses these signals during normal CPU/stack probing; pass them through.
handle SIGSEGV nostop noprint pass
handle SIGBUS nostop noprint pass

break Java_sun_awt_X11_XlibWrapper_XSetInputFocus
commands
silent
printf "TRACE Java_sun_awt_X11_XlibWrapper_XSetInputFocus thread=%d\n", $_thread
info registers rdi rsi rdx rcx r8 r9 rip
bt 24
continue
end

break Java_sun_awt_X11_XlibWrapper_XSetInputFocus2
commands
silent
printf "TRACE Java_sun_awt_X11_XlibWrapper_XSetInputFocus2 thread=%d\n", $_thread
info registers rdi rsi rdx rcx r8 r9 rip
bt 24
continue
end

break XSetInputFocus
commands
silent
printf "TRACE XSetInputFocus thread=%d\n", $_thread
info registers rdi rsi rdx rcx r8 r9 rip
bt 24
continue
end

break XSetErrorHandler
commands
silent
printf "TRACE XSetErrorHandler thread=%d\n", $_thread
info registers rdi rsi rdx rcx r8 r9 rip
info symbol $rdi
bt 24
continue
end

break _XDefaultError
commands
silent
printf "TRACE _XDefaultError thread=%d\n", $_thread
info registers rdi rsi rdx rcx r8 r9 rip
# Linux x64 XErrorEvent: display +8, resourceid +16, serial +24,
# error_code/request_code/minor_code at +32/+33/+34. Preserve raw bytes too.
x/40bx $rsi
bt 40
info sharedlibrary
thread apply all bt 12
continue
end

run
info breakpoints
info sharedlibrary
thread apply all bt 12
quit
