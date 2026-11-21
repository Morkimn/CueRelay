#ifndef PublishDir
  #error Specify PublishDir with /D
#endif
#ifndef ArtifactDir
  #error Specify ArtifactDir with /D
#endif
#ifndef BuildArchitecture
  #define BuildArchitecture "x64"
#endif

[Setup]
AppId={{16409B66-9E99-4705-9028-B5763469B80B}
AppName=CueRelay
AppVersion=0.7.2
DefaultDirName={autopf}\CueRelay
DefaultGroupName=CueRelay
UsePreviousGroup=no
UninstallDisplayIcon={app}\CueRelay.exe
OutputDir={#ArtifactDir}
OutputBaseFilename=CueRelay-Setup-{#BuildArchitecture}
#if BuildArchitecture == "arm64"
ArchitecturesAllowed=arm64
ArchitecturesInstallIn64BitMode=arm64
#else
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
#endif
MinVersion=10.0.19041
PrivilegesRequired=admin
WizardStyle=modern
DisableDirPage=yes
DisableProgramGroupPage=yes
Compression=lzma2
SolidCompression=yes
CloseApplications=no
RestartApplications=no
SetupMutex=CodexPocketPlayer-setup

[Languages]
Name: "russian"; MessagesFile: "compiler:Languages\Russian.isl"

[Files]
Source: "{#PublishDir}\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[InstallDelete]
Type: files; Name: "{app}\PocketPlayer.exe"
Type: files; Name: "{app}\PocketPlayer.dll"
Type: files; Name: "{app}\PocketPlayer.deps.json"
Type: files; Name: "{app}\PocketPlayer.runtimeconfig.json"

[Icons]
Name: "{group}\CueRelay"; Filename: "{app}\CueRelay.exe"
Name: "{group}\Удалить CueRelay"; Filename: "{uninstallexe}"

[Registry]
Root: HKCU; Subkey: "Software\Microsoft\Windows\CurrentVersion\Run"; ValueType: string; ValueName: "CodexPocketPlayer"; ValueData: """{app}\CueRelay.exe"" --background"; Flags: uninsdeletevalue; Check: ShouldEnableAutoStart

[Run]
Filename: "{app}\CueRelay.exe"; Flags: nowait postinstall runasoriginaluser skipifsilent; Description: "Открыть CueRelay"
Filename: "{app}\CueRelay.exe"; Flags: nowait runasoriginaluser; Check: IsUpdateInstall

[UninstallDelete]
Type: files; Name: "{localappdata}\CodexPocketPlayer\preferences.json"
Type: files; Name: "{localappdata}\CodexPocketPlayer\preferences.json.tmp"
Type: files; Name: "{localappdata}\CodexPocketPlayer\identity.bin"
Type: files; Name: "{localappdata}\CodexPocketPlayer\identity.bin.tmp"
Type: dirifempty; Name: "{localappdata}\CodexPocketPlayer"

[Code]
const
  FirewallRuleName = 'Pocket Player 43198 (Codex)';
  PlayerMutex = 'Local\CodexPocketPlayer-run';
  StopEventName = 'Local\CodexPocketPlayer-stop';
  AutoStartSettings = 'Software\CodexPocketPlayer';
  AutoStartDisableValue = 'AutoStartDisabled';
  RunRegistryKey = 'Software\Microsoft\Windows\CurrentVersion\Run';

function OpenEvent(DesiredAccess: LongWord; Inherit: Boolean; Name: string): THandle;
  external 'OpenEventW@kernel32.dll stdcall';
function SetEvent(Handle: THandle): Boolean;
  external 'SetEvent@kernel32.dll stdcall';
function CloseHandle(Handle: THandle): Boolean;
  external 'CloseHandle@kernel32.dll stdcall';

function ShouldEnableAutoStart(): Boolean;
var
  Disabled: Cardinal;
begin
  Result := not RegQueryDWordValue(HKCU, AutoStartSettings, AutoStartDisableValue, Disabled) or (Disabled = 0);
end;

function IsUpdateInstall(): Boolean;
begin
  Result := ExpandConstant('{param:CueRelayUpdate|0}') = '1';
end;

function StopPlayer(): Boolean;
var
  Code, Attempt: Integer;
  Handle: THandle;
begin
  if CheckForMutexes(PlayerMutex) then
  begin
    Handle := OpenEvent(2, False, StopEventName);
    if Handle <> 0 then
    begin
      SetEvent(Handle);
      CloseHandle(Handle);
    end
    else if FileExists(ExpandConstant('{app}\CueRelay.exe')) then
      Exec(ExpandConstant('{app}\CueRelay.exe'), '--stop', '', SW_HIDE, ewWaitUntilTerminated, Code)
    else if FileExists(ExpandConstant('{app}\PocketPlayer.exe')) then
      Exec(ExpandConstant('{app}\PocketPlayer.exe'), '--stop', '', SW_HIDE, ewWaitUntilTerminated, Code);
  end;
  for Attempt := 1 to 25 do
  begin
    if not CheckForMutexes(PlayerMutex) then Break;
    Sleep(200);
  end;
  Result := not CheckForMutexes(PlayerMutex);
end;

function PrepareToInstall(var NeedsRestart: Boolean): String;
begin
  Result := '';
  if not StopPlayer() then
    Result := 'Закрой CueRelay через меню в трее и повтори установку.';
end;

procedure RemoveFirewallRule();
var
  Policy: Variant;
begin
  Policy := CreateOleObject('HNetCfg.FwPolicy2');
  Policy.Rules.Remove(FirewallRuleName);
end;

procedure CurStepChanged(CurStep: TSetupStep);
var
  Policy, Rule: Variant;
begin
  if CurStep <> ssPostInstall then Exit;
  try
    Policy := CreateOleObject('HNetCfg.FwPolicy2');
    Policy.Rules.Remove(FirewallRuleName);
    Rule := CreateOleObject('HNetCfg.FWRule');
    Rule.Name := FirewallRuleName;
    Rule.Description := 'Зашифрованное подключение телефона к приложению CueRelay';
    Rule.ApplicationName := ExpandConstant('{app}\CueRelay.exe');
    Rule.Protocol := 6;
    Rule.LocalPorts := '43198';
    Rule.RemoteAddresses := 'LocalSubnet';
    Rule.Profiles := 2;
    Rule.Direction := 1;
    Rule.Action := 1;
    Rule.EdgeTraversal := False;
    Rule.Enabled := True;
    Policy.Rules.Add(Rule);
  except
    Log(GetExceptionMessage());
    SuppressibleMsgBox('Не удалось создать правило брандмауэра. Подключение телефона может быть недоступно.',
      mbError, MB_OK, IDOK);
  end;
end;

function InitializeUninstall(): Boolean;
begin
  Result := StopPlayer();
  if not Result then
    SuppressibleMsgBox('Закрой CueRelay через меню в трее и повтори удаление.', mbError, MB_OK, IDOK);
end;

procedure CurUninstallStepChanged(Step: TUninstallStep);
begin
  if Step <> usUninstall then Exit;
  RegDeleteValue(HKCU, RunRegistryKey, 'CodexPocketPlayer');
  RegDeleteValue(HKCU, AutoStartSettings, AutoStartDisableValue);
  RegDeleteKeyIfEmpty(HKCU, AutoStartSettings);
  try
    RemoveFirewallRule();
  except
    Log(GetExceptionMessage());
    SuppressibleMsgBox('Не удалось удалить правило брандмауэра Pocket Player 43198 (Codex).', mbError, MB_OK, IDOK);
  end;
end;
