@echo off
rem Stand-in for GNU make during rive-runtime's shader step; see make_shim.py.
python "%~dp0make_shim.py" %*
