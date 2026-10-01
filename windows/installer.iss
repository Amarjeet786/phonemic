[Setup]
AppName=Phone Mic Receiver
AppPublisher=Amarjeet K Gupta
AppVersion=0.3.0
DefaultDirName={autopf}\PhoneMic
DefaultGroupName=Phone Mic
OutputDir=..\installer
OutputBaseFilename=PhoneMic-Setup
Compression=lzma
SolidCompression=yes
PrivilegesRequired=lowest
ArchitecturesInstallIn64BitMode=x64compatible
UninstallDisplayName=Phone Mic Receiver

[Tasks]
Name: "desktopicon"; Description: "Create a desktop shortcut"; Flags: unchecked
Name: "autostart"; Description: "Start Phone Mic automatically with Windows"; Flags: unchecked
Name: "vbcable"; Description: "Open the VB-Cable download page. VB-Cable is a free virtual audio device; it is what lets Zoom, OBS and Discord use your phone as a microphone. Install it yourself, then choose 'CABLE Input' in Phone Mic."; Flags: unchecked

[Files]
Source: "..\publish\PhoneMic.Windows.exe"; DestDir: "{app}"; Flags: ignoreversion

[Icons]
Name: "{group}\Phone Mic"; Filename: "{app}\PhoneMic.Windows.exe"
Name: "{group}\Uninstall Phone Mic"; Filename: "{uninstallexe}"
Name: "{autodesktop}\Phone Mic"; Filename: "{app}\PhoneMic.Windows.exe"; Tasks: desktopicon

[Registry]
Root: HKCU; Subkey: "Software\Microsoft\Windows\CurrentVersion\Run"; ValueType: string; ValueName: "PhoneMic"; ValueData: """{app}\PhoneMic.Windows.exe"" --tray"; Flags: uninsdeletevalue; Tasks: autostart

[Run]
Filename: "https://vb-audio.com/Cable/"; Flags: shellexec; Tasks: vbcable
Filename: "{app}\PhoneMic.Windows.exe"; Description: "Launch Phone Mic"; Flags: nowait postinstall skipifsilent
