# LICENSE>>
# Copyright 2025 M5 Dev (FM Infinite Authors)
#
# Redistribution and use in source and binary forms, with or without modification, are permitted provided that the following conditions are met:
#
# 1. Redistributions of source code must retain the above copyright notice, this list of conditions and the following disclaimer.
# 2. Redistributions in binary form must reproduce the above copyright notice, this list of conditions and the following disclaimer in the documentation and/or other materials provided with the distribution.
# 3. Neither the name of the copyright holder nor the names of its contributors may be used to endorse or promote products derived from this software without specific prior written permission.
#
# THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED.
# << LICENSE

cmake_minimum_required(VERSION 3.22.1)
project(FMINFINITE_CORE)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_compile_definitions(YS_LITTLE_ENDIAN)
add_compile_definitions(YS_TWOS_COMPLEMENT)

set(CORE_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../../../src")

set(CORE_SOURCES
    # JNI Bridge
    bridge.cpp

    # Cheapmath
    "${CORE_ROOT}/cheapmath/cheapmath.cpp"

    # Cpputil
    "${CORE_ROOT}/cpputil/cpputil.cpp"

    # Lineparser
    "${CORE_ROOT}/lineparser/lineparser.cpp"

    # Miscutil
    "${CORE_ROOT}/miscutil/miscutil.cpp"
    "${CORE_ROOT}/miscutil/sjis2utf8.cpp"

    # Vmbase
    "${CORE_ROOT}/vmbase/vmbase.cpp"

    # Device
    "${CORE_ROOT}/device/device.cpp"
    "${CORE_ROOT}/device/deviceutil.cpp"

    # Cpu
    "${CORE_ROOT}/cpu/cpu.cpp"
    "${CORE_ROOT}/cpu/i486.cpp"
    "${CORE_ROOT}/cpu/i487.cpp"
    "${CORE_ROOT}/cpu/i486inst.cpp"
    "${CORE_ROOT}/cpu/i486operand.cpp"
    "${CORE_ROOT}/cpu/i486debug.cpp"
    "${CORE_ROOT}/cpu/i486debugmemaccess.cpp"
    "${CORE_ROOT}/cpu/i486symtable.cpp"
    "${CORE_ROOT}/cpu/i486symtableexp.cpp"
    "${CORE_ROOT}/cpu/i486statesave.cpp"
    "${CORE_ROOT}/cpu/i486debugVxD.cpp"
    "${CORE_ROOT}/cpu/i486garbage.cpp"

    # Inout
    "${CORE_ROOT}/inout/inout.cpp"

    # Ramrom
    "${CORE_ROOT}/ramrom/ramrom.cpp"

    # Ym2612
    "${CORE_ROOT}/ym2612/ym2612.cpp"
    "${CORE_ROOT}/ym2612/ym2612wave.cpp"

    # Rf5c68
    "${CORE_ROOT}/rf5c68/rf5c68.cpp"

    # I8251
    "${CORE_ROOT}/i8251/i8251.cpp"

    # I8253
    "${CORE_ROOT}/i8253/i8253.cpp"

    # Diskdrive
    "${CORE_ROOT}/diskdrive/diskdrive.cpp"

    # Customstick
    "${CORE_ROOT}/customstick/customstick.cpp"

    # Discimg
    "${CORE_ROOT}/discimg/discimg.cpp"

    # Diskimg
    "${CORE_ROOT}/diskimg/diskimg.cpp"
    "${CORE_ROOT}/diskimg/1232KB.c"
    "${CORE_ROOT}/diskimg/1440KB.c"
    "${CORE_ROOT}/diskimg/640KB.c"
    "${CORE_ROOT}/diskimg/720KB.c"

    # Vgmrecorder
    "${CORE_ROOT}/vgmrecorder/vgmrecorder.cpp"
    "${CORE_ROOT}/vgmrecorder/vgmrecorder_ym2612.cpp"
    "${CORE_ROOT}/vgmrecorder/vgmrecorder_rf5c68.cpp"
    "${CORE_ROOT}/vgmrecorder/vgmrecorder_ym2203.cpp"
    "${CORE_ROOT}/vgmrecorder/vgmrecorder_ay8910.cpp"

    # d77
    "${CORE_ROOT}/externals/d77/d77.cpp"
    "${CORE_ROOT}/externals/d77/d77ext.cpp"

    # ← ده اللي كان ناقص! YsSoundPlayer
    "${CORE_ROOT}/externals/yssimplesound/src/yssimplesound.cpp"
    "${CORE_ROOT}/externals/yssimplesound/src/nownd/yssimplesound_nownd.cpp"

    # ← ده اللي كان ناقص! YsPng
    "${CORE_ROOT}/externals/yspng/yspngenc.cpp"

    # I8251 to Socket & Yssocket
    "${CORE_ROOT}/i8251_to_socket/i8251tosocket.cpp"
    "${CORE_ROOT}/externals/yssocket/src/yssocket.cpp"

    # Osdependent MIDI and File System
    "${CORE_ROOT}/osdependent/midi/midi_interface.cpp"
    "${CORE_ROOT}/osdependent/midi/null/midi_implement.cpp"
    "${CORE_ROOT}/osdependent/filesys/filesys_common.cpp"
    "${CORE_ROOT}/osdependent/filesys/filesys_unix.cpp"

    # Towns Outside World
    "${CORE_ROOT}/towns/outside_world/outside_world.cpp"
    "${CORE_ROOT}/towns/outside_world/icons.cpp"
    "${CORE_ROOT}/towns/outside_world/headless_mode.cpp"

    # Towns Def
    "${CORE_ROOT}/towns/townsdef/townsdef.cpp"
    "${CORE_ROOT}/towns/townsdef/townsmap.cpp"

    # Towns Param
    "${CORE_ROOT}/towns/townsparam/townsparam.cpp"
    "${CORE_ROOT}/towns/townsparam/townsoptions.cpp"
    "${CORE_ROOT}/towns/townsparam/townsprofile.cpp"

    # Towns Tips
    "${CORE_ROOT}/towns/townstips/townstips.cpp"

    # Towns CDROM
    "${CORE_ROOT}/towns/cdrom/cdrom.cpp"

    # Towns Memory
    "${CORE_ROOT}/towns/memory/physmem.cpp"
    "${CORE_ROOT}/towns/memory/memaccess.cpp"
    "${CORE_ROOT}/towns/memory/memcard.cpp"

    # Towns CRTC
    "${CORE_ROOT}/towns/crtc/crtcbase.cpp"
    "${CORE_ROOT}/towns/crtc/crtc.cpp"
    "${CORE_ROOT}/towns/crtc/fmt3631.cpp"

    # Towns Sprite
    "${CORE_ROOT}/towns/sprite/sprite.cpp"

    # Towns Render
    "${CORE_ROOT}/towns/render/render.cpp"

    # Towns Keyboard
    "${CORE_ROOT}/towns/keyboard/keyboard.cpp"
    "${CORE_ROOT}/towns/keyboard/keytrans.cpp"

    # Towns FDC
    "${CORE_ROOT}/towns/fdc/fdc.cpp"

    # Towns SCSI
    "${CORE_ROOT}/towns/scsi/scsi.cpp"

    # Towns PIC
    "${CORE_ROOT}/towns/pic/pic.cpp"

    # Towns RTC
    "${CORE_ROOT}/towns/rtc/rtc.cpp"

    # Towns DMAC
    "${CORE_ROOT}/towns/dmac/dmac.cpp"

    # Towns Sound
    "${CORE_ROOT}/towns/sound/sound.cpp"

    # Towns MIDI
    "${CORE_ROOT}/towns/midi/midi.cpp"

    # Towns Timer
    "${CORE_ROOT}/towns/timer/timer.cpp"

    # Towns Serial
    "${CORE_ROOT}/towns/serialport/serialport.cpp"

    # Towns Gameport
    "${CORE_ROOT}/towns/gameport/gameport.cpp"
    "${CORE_ROOT}/towns/gameport/cyberstick.cpp"

    # Towns Eventlog
    "${CORE_ROOT}/towns/eventlog/eventlog.cpp"

    # Towns VNDRV
    "${CORE_ROOT}/towns/vndrv/vndrv.cpp"

    # Towns TGDRV
    "${CORE_ROOT}/towns/tgdrv/tgdrv.cpp"

    # Towns HighresPCM
    "${CORE_ROOT}/towns/highrespcm/highrespcm.cpp"

    # Towns Rex3586 (network)
    "${CORE_ROOT}/towns/lan_rex3586/virtual_network.cpp"
    "${CORE_ROOT}/towns/lan_rex3586/real_network.cpp"
    "${CORE_ROOT}/towns/lan_rex3586/rex3586.cpp"

    # Towns Common Core
    "${CORE_ROOT}/towns/towns.cpp"
    "${CORE_ROOT}/towns/townsstate.cpp"
    "${CORE_ROOT}/towns/tbiosid.cpp"
    "${CORE_ROOT}/towns/townscmos.cpp"
    "${CORE_ROOT}/towns/townsio.cpp"
    "${CORE_ROOT}/towns/townsvmif.cpp"
    "${CORE_ROOT}/towns/townsthread.cpp"
    "${CORE_ROOT}/towns/townsapp_dunmas.cpp"
    "${CORE_ROOT}/towns/townsapp_daikoukai.cpp"
    "${CORE_ROOT}/towns/townsapp_daikoukai2.cpp"
    "${CORE_ROOT}/towns/townsapp_ab2.cpp"

    # Command parser
    "${CORE_ROOT}/main_cui/command/townscommand.cpp"
    "${CORE_ROOT}/main_cui/command/townscommandutil.cpp"
    "${CORE_ROOT}/main_cui/command/townslineparser.cpp"
)

add_library(fminfinite_core SHARED ${CORE_SOURCES})

target_include_directories(fminfinite_core PRIVATE
    "${CORE_ROOT}"
    "${CORE_ROOT}/cheapmath"
    "${CORE_ROOT}/cpputil"
    "${CORE_ROOT}/lineparser"
    "${CORE_ROOT}/miscutil"
    "${CORE_ROOT}/vmbase"
    "${CORE_ROOT}/device"
    "${CORE_ROOT}/cpu"
    "${CORE_ROOT}/inout"
    "${CORE_ROOT}/ramrom"
    "${CORE_ROOT}/ym2612"
    "${CORE_ROOT}/rf5c68"
    "${CORE_ROOT}/i8251"
    "${CORE_ROOT}/i8253"
    "${CORE_ROOT}/diskdrive"
    "${CORE_ROOT}/customstick"
    "${CORE_ROOT}/discimg"
    "${CORE_ROOT}/diskimg"
    "${CORE_ROOT}/vgmrecorder"
    "${CORE_ROOT}/i8251_to_socket"
    "${CORE_ROOT}/externals/d77"
    "${CORE_ROOT}/externals/yssimplesound/src"
    "${CORE_ROOT}/externals/yspng"
    "${CORE_ROOT}/externals/yssocket/src"
    "${CORE_ROOT}/osdependent/midi"
    "${CORE_ROOT}/osdependent/filesys"
    "${CORE_ROOT}/towns"
    "${CORE_ROOT}/towns/outside_world"
    "${CORE_ROOT}/towns/townsdef"
    "${CORE_ROOT}/towns/townsparam"
    "${CORE_ROOT}/towns/townstips"
    "${CORE_ROOT}/towns/cdrom"
    "${CORE_ROOT}/towns/memory"
    "${CORE_ROOT}/towns/crtc"
    "${CORE_ROOT}/towns/sprite"
    "${CORE_ROOT}/towns/render"
    "${CORE_ROOT}/towns/keyboard"
    "${CORE_ROOT}/towns/fdc"
    "${CORE_ROOT}/towns/scsi"
    "${CORE_ROOT}/towns/pic"
    "${CORE_ROOT}/towns/rtc"
    "${CORE_ROOT}/towns/dmac"
    "${CORE_ROOT}/towns/sound"
    "${CORE_ROOT}/towns/midi"
    "${CORE_ROOT}/towns/timer"
    "${CORE_ROOT}/towns/serialport"
    "${CORE_ROOT}/towns/gameport"
    "${CORE_ROOT}/towns/eventlog"
    "${CORE_ROOT}/towns/vndrv"
    "${CORE_ROOT}/towns/tgdrv"
    "${CORE_ROOT}/towns/highrespcm"
    "${CORE_ROOT}/towns/lan_rex3586"
    "${CORE_ROOT}/main_cui/command"
)

target_link_libraries(fminfinite_core
    android
    log
    OpenSLES
    EGL
    GLESv2
)
