sub init()
    m.top.backgroundColor = "0x0A0A0BFF"
    m.top.backgroundUri = ""
    m.screens = m.top.findNode("screens")
    m.stack = []
    m.resumePending = false

    ' Render-thread node shared with DataService: requests go in, state comes out.
    ' Keeping it render-owned means the UI never blocks on the task thread.
    bus = CreateObject("roSGNode", "Node")
    bus.addField("request", "assocarray", true)
    bus.addFields({ ready: false, status: "", playlistState: "idle", epgState: "idle", categories: [], channelCount: 0, epgVersion: 0, playerOpen: false, playingSince: 0, prefsVersion: 0 })
    ' playerOpen / playingSince (epoch secs, 0 = not playing) come from the player, so the data
    ' service can hold heavy guide work until a starting stream has settled.
    m.global.addFields({ bus: bus })
    bus.observeFieldScoped("playlistState", "onPlaylistState")

    svc = CreateObject("roSGNode", "DataService")
    m.global.addFields({ svc: svc })
    svc.control = "RUN"
    ensureRemote()

    if regRead("m3uUrl") = "" then
        pushScreen("SetupScreen", { firstRun: true })
    else
        startLoad(false) ' always open on the home screen (not the last channel)
        pushScreen("HomeScreen", {})
    end if
end sub

sub startLoad(resume as boolean)
    m.resumePending = resume and regRead("lastKey") <> ""
    m.pendingLoad = { type: "load", m3u: regRead("m3uUrl"), epg: regRead("epgUrl"), resume: m.resumePending }
    if m.global.bus.ready then
        sendPendingLoad()
    else
        ' The task thread may not be listening yet on a cold start; wait for it.
        m.global.bus.observeFieldScoped("ready", "sendPendingLoad")
    end if
end sub

sub sendPendingLoad()
    if not m.global.bus.ready or m.pendingLoad = invalid then return
    m.global.bus.unobserveFieldScoped("ready")
    req = m.pendingLoad
    m.pendingLoad = invalid
    m.global.bus.request = req
end sub

' Last-watched channel on launch: once the playlist is ready, jump straight into it.
sub onPlaylistState()
    if m.global.bus.playlistState <> "ready" or not m.resumePending then return
    m.resumePending = false
    if m.stack.Count() = 1 and m.stack[0].subtype() = "HomeScreen" then
        pushScreen("PlayerScreen", { category: regRead("lastCategory", "__all__"), key: regRead("lastKey") })
    end if
end sub

sub pushScreen(name as string, params as object)
    if m.stack.Count() > 0 then
        top = m.stack.Peek()
        top.active = false
        top.visible = false
    end if
    screen = m.screens.createChild(name)
    screen.observeField("navigate", "onNavigate")
    screen.params = params
    m.stack.Push(screen)
    screen.active = true
end sub

sub closeScreen(screen as object)
    screen.active = false
    screen.closed = true
    screen.unobserveField("navigate")
    m.screens.removeChild(screen)
end sub

sub popScreen()
    if m.stack.Count() <= 1 then return
    closeScreen(m.stack.Pop())
    top = m.stack.Peek()
    top.visible = true
    top.active = true
end sub

sub onNavigate(event as object)
    nav = event.getData()
    if nav = invalid or nav.action = invalid then return
    params = nav.params
    if params = invalid then params = {}
    if nav.action = "push" then
        pushScreen(nav.screen, params)
    else if nav.action = "pop" then
        popScreen()
    else if nav.action = "replace" then
        if m.stack.Count() > 0 then closeScreen(m.stack.Pop())
        pushScreen(nav.screen, params)
    else if nav.action = "reset" then
        while m.stack.Count() > 0
            closeScreen(m.stack.Pop())
        end while
        if nav.load <> invalid and nav.load then startLoad(false)
        ensureRemote() ' a setup code may have just linked this Roku
        pushScreen(nav.screen, params)
    end if
end sub

function onKeyEvent(key as string, press as boolean) as boolean
    if press and key = "back" and m.stack.Count() > 1 then
        popScreen()
        return true
    end if
    return false
end function

' ---------------------------------------------------------------- remote control

' Listens for commands from the phone app / tv.thecoxhome.com once this Roku is linked.
sub ensureRemote()
    if regRead("deviceToken") = "" then return
    if m.global.hasField("remote") and m.global.remote <> invalid then return
    task = CreateObject("roSGNode", "RemoteTask")
    task.observeField("command", "onRemoteCommand")
    task.observeField("unlinked", "onRemoteUnlinked")
    if m.global.hasField("remote") then
        m.global.remote = task
    else
        m.global.addFields({ remote: task })
    end if
    task.control = "RUN"
end sub

sub onRemoteUnlinked()
    print "CoxTV: unlinked from the stream server"
end sub

sub onRemoteCommand(event as object)
    c = event.getData()
    if c = invalid or c.cmd = invalid or m.stack.Count() = 0 then return
    top = m.stack.Peek()
    if top.subtype() = "SetupScreen" and m.stack.Count() = 1 then return ' not set up yet
    onPlayer = top.subtype() = "PlayerScreen"
    print "CoxTV: remote "; c.cmd; " "; c.name
    if c.cmd = "play" then
        m.remoteReply = svcCall({ type: "serverKey", id: c.id }, "onServerKey")
    else if c.cmd = "stop" then
        if onPlayer then popScreen()
    else if onPlayer then
        top.remote = c.cmd ' up / down / prev
    else if c.cmd = "prev" and regRead("lastKey") <> "" then
        pushScreen("PlayerScreen", { category: regRead("lastCategory", "__all__"), key: regRead("lastKey") })
    end if
end sub

' The channel sent from the remote, found in this Roku's playlist: play it (replacing the
' player if one is open).
sub onServerKey(event as object)
    if m.remoteReply = invalid or not event.getRoSGNode().isSameNode(m.remoteReply) then return
    m.remoteReply = invalid
    key = event.getData().key
    if key = invalid or key = "" then return
    if m.stack.Count() > 0 and m.stack.Peek().subtype() = "PlayerScreen" then closeScreen(m.stack.Pop())
    pushScreen("PlayerScreen", { category: "__all__", key: key })
end sub
