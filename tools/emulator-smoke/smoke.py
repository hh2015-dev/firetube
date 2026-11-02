"""Actual emulator smoke test; no Google account, no real Drive download/playback."""
import importlib.util
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time
import hashlib

BASE=Path(__file__).resolve().parents[2]
spec=importlib.util.spec_from_file_location('upgrade',BASE/'tools/upgrade-tests/verify_drive_offline_upgrade.py')
u=importlib.util.module_from_spec(spec);spec.loader.exec_module(u)
REPORT=Path('smoke-report');REPORT.mkdir(exist_ok=True)
result={'kind':'EMULATOR_SMOKE_ONLY','google_account_used':False,'real_drive_download':'NOT_TESTED','offline_playback':'NOT_TESTED','steps':{}}

def mark(name,value=True):
    result['steps'][name]=value
    (REPORT/'result.json').write_text(json.dumps(result,indent=2)+'\n')
    print(name+': '+str(value),flush=True)

def start(serial):
    resolved=u.adb('-s',serial,'shell','cmd','package','resolve-activity','--brief',u.PACKAGE).splitlines()[-1]
    if not resolved.startswith(u.PACKAGE+'/'):raise RuntimeError('Unexpected launch activity')
    out=u.adb('-s',serial,'shell','am','start','-W','-n',resolved)
    if 'Status: ok' not in out:raise RuntimeError('Activity start failed')
    deadline=time.monotonic()+30
    while time.monotonic()<deadline:
        activity=u.adb('-s',serial,'shell','dumpsys','activity','activities')
        if any(u.PACKAGE in line and ('mResumedActivity' in line or 'topResumedActivity' in line) for line in activity.splitlines()):return
        time.sleep(2)
    raise RuntimeError('App did not reach resumed foreground state')

try:
    serial=u.check_emulator();mark('emulator_verified',serial)
    mark('api_level',u.adb('-s',serial,'shell','getprop','ro.build.version.sdk'))
    packages=u.adb('-s',serial,'shell','pm','list','packages')
    mark('play_services_installed','package:com.google.android.gms' in packages)
    mark('play_store_installed','package:com.android.vending' in packages)
    root=Path(os.environ['RUNNER_TEMP'])/'firetube-private'
    for version in [215,217]:
        if u.inspect_apk(root/(str(version)+'.apk'))!=version:raise RuntimeError('APK version mismatch')
        mark('original_'+str(version)+'_signature_package',True)
    # A freshly created disposable emulator must not contain an existing user installation.
    if ('package:'+u.PACKAGE) in packages.splitlines():raise RuntimeError('Expected empty disposable emulator')
    mark('empty_disposable_emulator_confirmed',True)
    u.adb('-s',serial,'install','-g',str(root/'215.apk'));mark('install_original_215',u.installed_version(serial)==215)
    if u.installed_version(serial)!=215:raise RuntimeError('Old install version mismatch')
    start(serial);mark('launch_215_foreground',True)
    u.adb('-s',serial,'exec-out','run-as',u.PACKAGE,'id');mark('run_as_access_215',True)
    # A tiny, explicitly synthetic sentinel outside the Drive audio directory.
    # This tests package-private file retention only; it cannot claim Drive download survival.
    u.adb('-s',serial,'shell',"run-as "+u.PACKAGE+" sh -c 'mkdir -p no_backup && printf firetube-smoke-215 > no_backup/emulator-upgrade-sentinel.txt'")
    before=u.adb('-s',serial,'exec-out','run-as',u.PACKAGE,'cat','no_backup/emulator-upgrade-sentinel.txt',binary=True)
    if before!=b'firetube-smoke-215':raise RuntimeError('Sentinel setup failed')
    mark('synthetic_sentinel_created',True)
    u.adb('-s',serial,'install','-r',str(root/'217.apk'))
    if u.installed_version(serial)!=217:raise RuntimeError('Upgrade version mismatch')
    mark('in_place_upgrade_215_to_217',True)
    start(serial);mark('launch_217_foreground',True)
    after=u.adb('-s',serial,'exec-out','run-as',u.PACKAGE,'cat','no_backup/emulator-upgrade-sentinel.txt',binary=True)
    if before!=after:raise RuntimeError('Synthetic sentinel changed after upgrade')
    mark('synthetic_private_file_preserved',True)
    result['status']='PASS_SMOKE_ONLY'
except Exception as exc:
    result['status']='FAIL_SMOKE'
    # No raw device/logcat dumps, private URLs or account credentials.
    result['error_type']=type(exc).__name__
    result['safe_error']=str(exc) if isinstance(exc,RuntimeError) else 'Non-runtime error (details suppressed)'
    print('Smoke test failed: '+result['safe_error'],flush=True)
finally:
    (REPORT/'result.json').write_text(json.dumps(result,indent=2)+'\n')
    print(json.dumps(result,indent=2),flush=True)
if result['status']!='PASS_SMOKE_ONLY':sys.exit(1)
