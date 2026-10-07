sub init()
    m.menu = m.top.findNode("menu")
    m.status = m.top.findNode("status")
    m.menu.observeField("itemSelected", "onSelected")
    m.top.observeField("active", "onActive")
    m.top.observeField("params", "onParams")
    m.firstRun = true
    m.m3u = regRead("m3uUrl")
    m.epg = regRead("epgUrl")
    m.xtServer = regRead("xtServer")
    m.xtUser = regRead("xtUser")
    m.xtPass = regRead("xtPass")
    m.dialog = invalid
    m.nextEdit = invalid
    m.pendingEdit = invalid
    m.nextTimer = m.top.findNode("nextTimer")
    m.nextTimer.observeField("fire", "openPendingEdit")
    refreshMenu()
end sub

sub onParams()
    p = m.top.params
    m.firstRun = true
    if p.firstRun <> invalid then m.firstRun = p.firstRun
    refreshMenu()
end sub

sub onActive()
    if m.top.active and m.dialog = invalid then m.menu.setFocus(true)
end sub

sub refreshMenu()
    labels = [
        "Enter setup code  (from tv.thecoxhome.com > Watch > Set up a TV)"
        "Playlist (M3U) URL:   " + valueOr(m.m3u, "not set")
        "Guide (XMLTV) URL:   " + valueOr(m.epg, "optional")
        "Enter Xtream login instead (server, username, password)"
        "Save and load channels"
    ]
    if not m.firstRun then
        labels.Push("Categories:  choose which show in the sidebar, and their order")
        labels.Push("Reorder favorites")
        labels.Push("Hidden channels")
        labels.Push("Favorite teams:  NFL, college (every NCAA sport), NBA, WNBA, MLB, NHL, soccer")
        if regRead("deviceToken") <> "" then
            if useStreamServer() then state = "On" else state = "Off"
            labels.Push("Play through stream server:  " + state + "   (linked as " + valueOr(regRead("deviceName"), "this Roku") + ")")
        end if
    end if
    root = CreateObject("roSGNode", "ContentNode")
    for each label in labels
        root.CreateChild("ContentNode").title = label
    end for
    focused = m.menu.itemFocused
    m.menu.content = root
    if focused > 0 then m.menu.jumpToItem = focused
end sub

function valueOr(value as string, fallback as string) as string
    if value = "" then return fallback
    return value
end function

sub onSelected()
    i = m.menu.itemSelected
    m.status.text = ""
    m.status.color = "0xFF6B6BFF"
    if i = 0 then
        editField("code", "Setup code from tv.thecoxhome.com (like K7P-2QX)", "")
    else if i = 1 then
        editField("m3u", "Playlist (M3U) URL", m.m3u)
    else if i = 2 then
        editField("epg", "Guide (XMLTV) URL - optional", m.epg)
    else if i = 3 then
        editField("xtServer", "Xtream server, e.g. example.com:80 (http:// is optional)", m.xtServer)
    else if i = 4 then
        save()
    else if i = 5 then
        m.top.navigate = { action: "push", screen: "OrganizeScreen", params: { mode: "categories" } }
    else if i = 6 then
        m.top.navigate = { action: "push", screen: "OrganizeScreen", params: { mode: "favorites" } }
    else if i = 7 then
        m.top.navigate = { action: "push", screen: "OrganizeScreen", params: { mode: "hidden" } }
    else if i = 8 then
        m.top.navigate = { action: "push", screen: "OrganizeScreen", params: { mode: "teams" } }
    else if i = 9 then
        if useStreamServer() then regWrite("useServer", "0") else regWrite("useServer", "1")
        m.status.color = "0x9AA3B2FF"
        if useStreamServer() then
            m.status.text = "Channels play through your stream server (TVs on the same channel share one connection)."
        else
            m.status.text = "Channels play straight from the provider."
        end if
        refreshMenu()
    end if
end sub

sub editField(which as string, title as string, value as string)
    dlg = CreateObject("roSGNode", "StandardKeyboardDialog")
    if dlg = invalid then dlg = CreateObject("roSGNode", "KeyboardDialog") ' Roku OS < 10
    raiseTextLimit(dlg) ' must happen before setting text, or long values get truncated
    dlg.title = title
    dlg.text = value
    dlg.buttons = ["OK", "Cancel"]
    dlg.observeFieldScoped("buttonSelected", "onDialogButton")
    dlg.observeFieldScoped("wasClosed", "onDialogClosed")
    m.editing = which
    m.dialog = dlg
    m.top.getScene().dialog = dlg
end sub

' Roku's TextEditBox stops at 75 characters by default - too short for provider URLs.
sub raiseTextLimit(dlg as object)
    teb = invalid
    if dlg.hasField("textEditBox") then teb = dlg.textEditBox
    if teb = invalid and dlg.hasField("keyboard") then
        kb = dlg.keyboard
        if kb <> invalid and kb.hasField("textEditBox") then teb = kb.textEditBox
    end if
    if teb = invalid then teb = findTextEditBox(dlg, 0)
    if teb <> invalid and teb.hasField("maxTextLength") then teb.maxTextLength = 1000
end sub

function findTextEditBox(node as object, depth as integer) as dynamic
    if node = invalid or depth > 6 then return invalid
    if node.subtype() = "TextEditBox" then return node
    for i = 0 to node.getChildCount() - 1
        found = findTextEditBox(node.getChild(i), depth + 1)
        if found <> invalid then return found
    end for
    return invalid
end function

sub onDialogButton()
    if m.dialog = invalid then return
    m.nextEdit = invalid
    if m.dialog.buttonSelected = 0 then
        value = m.dialog.text.Trim()
        if m.editing = "code" then
            redeemCode(value)
        else if m.editing = "m3u" then
            m.m3u = value
        else if m.editing = "epg" then
            m.epg = value
        else if m.editing = "xtServer" then
            m.xtServer = value
            m.nextEdit = { which: "xtUser", title: "Xtream username", value: m.xtUser }
        else if m.editing = "xtUser" then
            m.xtUser = value
            m.nextEdit = { which: "xtPass", title: "Xtream password", value: m.xtPass }
        else if m.editing = "xtPass" then
            m.xtPass = value
            applyXtreamLogin()
        end if
        refreshMenu()
    end if
    ' Detach before closing: close=true fires wasClosed immediately, which would
    ' otherwise be mistaken for the Back key and cancel the next login step.
    dlg = m.dialog
    dlg.unobserveFieldScoped("buttonSelected")
    dlg.unobserveFieldScoped("wasClosed")
    m.dialog = invalid
    dlg.close = true
    finishDialog()
end sub

' Back key closes the dialog without a button press.
sub onDialogClosed()
    if m.dialog <> invalid then
        m.nextEdit = invalid
        finishDialog()
    end if
end sub

' Runs right after a dialog closes: open the next login step, or give focus back to the
' menu. Done directly (not only on wasClosed) so focus is never left on a closed dialog.
sub finishDialog()
    if m.dialog <> invalid then
        m.dialog.unobserveFieldScoped("buttonSelected")
        m.dialog.unobserveFieldScoped("wasClosed")
    end if
    m.dialog = invalid
    m.top.getScene().dialog = invalid
    m.menu.setFocus(true)
    if m.nextEdit <> invalid then
        ' Open the next login step only after this dialog has finished closing;
        ' a dialog opened in the same instant is dismissed along with the old one.
        m.pendingEdit = m.nextEdit
        m.nextEdit = invalid
        m.nextTimer.control = "start"
    end if
end sub

sub openPendingEdit()
    nxt = m.pendingEdit
    m.pendingEdit = invalid
    if nxt <> invalid then editField(nxt.which, nxt.title, nxt.value)
end sub

' Builds the standard Xtream Codes playlist (live channels) and guide URLs from a login.
sub applyXtreamLogin()
    server = m.xtServer.Trim()
    if server = "" or m.xtUser = "" then
        m.status.text = "Enter the server and username"
        return
    end if
    lower = LCase(server)
    if not (Left(lower, 7) = "http://" or Left(lower, 8) = "https://") then server = "http://" + server
    while Right(server, 1) = "/"
        server = Left(server, Len(server) - 1)
    end while
    for each suffix in ["/get.php", "/player_api.php", "/xmltv.php"]
        if LCase(Right(server, Len(suffix))) = suffix then server = Left(server, Len(server) - Len(suffix))
    end for
    esc = CreateObject("roUrlTransfer")
    creds = "username=" + esc.Escape(m.xtUser) + "&password=" + esc.Escape(m.xtPass)
    m.xtServer = server
    m.m3u = server + "/get.php?" + creds + "&type=m3u_plus&output=ts"
    m.epg = server + "/xmltv.php?" + creds
    regWrite("xtServer", m.xtServer)
    regWrite("xtUser", m.xtUser)
    regWrite("xtPass", m.xtPass)
    m.status.color = "0x5BD68AFF"
    m.status.text = "Playlist and guide URLs filled in from your login - choose Save and load channels"
end sub

sub save()
    lower = LCase(m.m3u)
    if not (Left(lower, 7) = "http://" or Left(lower, 8) = "https://") then
        m.status.text = "Enter a playlist URL starting with http:// or https://"
        return
    end if
    if m.epg <> "" and not (Left(LCase(m.epg), 7) = "http://" or Left(LCase(m.epg), 8) = "https://") then
        m.status.text = "The guide URL must start with http:// or https:// (or leave it empty)"
        return
    end if
    regWrite("m3uUrl", m.m3u)
    regWrite("epgUrl", m.epg)
    m.top.navigate = { action: "reset", screen: "HomeScreen", load: true }
end sub

' ---------------------------------------------------------------- setup code

' Exchanges a code from tv.thecoxhome.com for the playlist and guide links (the background
' service does the network request), then saves them and loads the channels.
sub redeemCode(code as string)
    clean = ""
    up = UCase(code)
    for k = 1 to Len(up)
        c = Mid(up, k, 1)
        if (c >= "A" and c <= "Z") or (c >= "0" and c <= "9") then clean = clean + c
    end for
    if Len(clean) <> 6 then
        m.status.text = "Setup codes have 6 letters and numbers, like K7P-2QX."
        return
    end if
    m.status.color = "0x9AA3B2FF"
    m.status.text = "Checking the code..."
    m.codeReply = svcCall({ type: "setupCode", code: clean }, "onCodeReply")
end sub

sub onCodeReply(event as object)
    if m.codeReply = invalid or not event.getRoSGNode().isSameNode(m.codeReply) then return
    m.codeReply = invalid
    r = event.getData()
    if r.error <> invalid and r.error <> "" then
        m.status.color = "0xFF6B6BFF"
        m.status.text = r.error
        return
    end if
    if r.device <> invalid and r.device <> "" then
        regWrite("deviceToken", r.device)
        regWrite("deviceName", r.deviceName)
    end if
    m.m3u = r.m3u
    ' The stream server's ready-made guide loads in seconds on a Roku; the provider's raw
    ' guide takes minutes.
    m.epg = r.guide
    m.status.color = "0x5BD68AFF"
    m.status.text = "Got your links - loading channels..."
    refreshMenu()
    save()
end sub
