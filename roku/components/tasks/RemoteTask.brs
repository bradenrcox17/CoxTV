sub init()
    m.top.functionName = "listen"
end sub

sub listen()
    m.port = CreateObject("roMessagePort")
    m.top.observeField("playing", m.port)
    m.top.observeField("leave", m.port)
    m.clock = CreateObject("roTimespan")
    m.since = "0"        ' newest command id handled (13-digit string, compared as text)
    m.failures = 0
    m.poll = invalid
    m.pollId = ""
    m.nextPoll = 0
    m.posts = {}         ' in-flight POSTs, kept alive until answered
    while true
        if m.poll = invalid and m.clock.TotalSeconds() >= m.nextPoll then startPoll()
        msg = wait(1000, m.port)
        if type(msg) = "roUrlEvent" then
            id = msg.GetSourceIdentity().ToStr()
            if m.poll <> invalid and id = m.pollId then
                m.poll = invalid
                onPollDone(msg)
            else if m.posts.DoesExist(id) then
                m.posts.Delete(id)
            end if
        else if type(msg) = "roSGNodeEvent" then
            field = msg.getField()
            if field = "playing" then
                ' Report the new channel right away instead of at the end of the current poll.
                if m.poll <> invalid then
                    m.poll.AsyncCancel()
                    m.poll = invalid
                    m.nextPoll = 0
                end if
            else if field = "leave" then
                sendLeave()
            end if
        end if
    end while
end sub

function newTransfer(url as string) as object
    ut = CreateObject("roUrlTransfer")
    ut.SetMessagePort(m.port)
    ut.SetUrl(url)
    ut.SetCertificatesFile("common:/certs/ca-bundle.crt")
    ut.InitClientCertificates()
    ut.RetainBodyOnError(true)
    return ut
end function

sub startPoll()
    token = regRead("deviceToken")
    if token = "" then
        m.nextPoll = m.clock.TotalSeconds() + 30
        return
    end if
    playing = m.top.playing
    name = ""
    id = ""
    if playing <> invalid then
        if playing.name <> invalid then name = playing.name
        if playing.id <> invalid then id = playing.id
    end if
    esc = CreateObject("roUrlTransfer")
    url = streamServer() + "/d/" + token + "/wait?since=" + m.since + "&ch=" + esc.Escape(name) + "&id=" + esc.Escape(id)
    ut = newTransfer(url)
    if ut.AsyncGetToString() then
        m.poll = ut
        m.pollId = ut.GetIdentity().ToStr()
    else
        m.nextPoll = m.clock.TotalSeconds() + 10
    end if
end sub

sub onPollDone(msg as object)
    code = msg.GetResponseCode()
    if code = 200 then
        m.failures = 0
        m.nextPoll = 0
        data = ParseJson(msg.GetString())
        if data = invalid then return
        if data.commands <> invalid then
            for each c in data.commands
                nid = c.nid
                if nid <> invalid and newerId(nid, m.since) then
                    m.since = nid
                    out = { cmd: c.cmd, id: "", name: "" }
                    if c.id <> invalid then out.id = c.id
                    if c.name <> invalid then out.name = c.name
                    m.top.command = out
                end if
            end for
        end if
        if data.name <> invalid and data.name <> "" and data.name <> regRead("deviceName") then regWrite("deviceName", data.name)
    else if code = 401 then
        ' Unlinked on tv.thecoxhome.com: stop using the server until set up with a new code.
        regWrite("deviceToken", "")
        m.top.unlinked = true
        m.nextPoll = m.clock.TotalSeconds() + 30
    else
        ' Offline, server restarting, or a cancelled poll: back off a little.
        m.failures = m.failures + 1
        delay = 5 * m.failures
        if delay > 60 then delay = 60
        m.nextPoll = m.clock.TotalSeconds() + delay
    end if
end sub

' Command ids are millisecond timestamps as text; longer means newer, else compare as text.
function newerId(a as string, b as string) as boolean
    if Len(a) <> Len(b) then return Len(a) > Len(b)
    return a > b
end function

sub sendLeave()
    token = regRead("deviceToken")
    if token = "" then return
    ut = newTransfer(streamServer() + "/d/" + token + "/leave")
    ut.SetRequest("POST")
    ut.AddHeader("Content-Type", "application/json")
    if ut.AsyncPostFromString("{}") then m.posts[ut.GetIdentity().ToStr()] = ut
end sub
