sub init()
    m.video = m.top.findNode("video")
    m.loading = m.top.findNode("loading")
    m.errorBox = m.top.findNode("errorBox")
    m.errorTitle = m.top.findNode("errorTitle")
    m.errorMsg = m.top.findNode("errorMsg")
    m.info = m.top.findNode("info")
    m.infoInitials = m.top.findNode("infoInitials")
    m.infoLogo = m.top.findNode("infoLogo")
    m.infoNum = m.top.findNode("infoNum")
    m.infoFav = m.top.findNode("infoFav")
    m.infoName = m.top.findNode("infoName")
    m.infoClock = m.top.findNode("infoClock")
    m.infoTitle = m.top.findNode("infoTitle")
    m.infoTime = m.top.findNode("infoTime")
    m.infoBarBg = m.top.findNode("infoBarBg")
    m.infoBar = m.top.findNode("infoBar")
    m.infoLeft = m.top.findNode("infoLeft")
    m.infoDesc = m.top.findNode("infoDesc")
    m.infoNext = m.top.findNode("infoNext")
    m.mini = m.top.findNode("mini")
    m.miniTitle = m.top.findNode("miniTitle")
    m.miniList = m.top.findNode("miniList")
    m.infoTimer = m.top.findNode("infoTimer")
    m.zapTimer = m.top.findNode("zapTimer")
    m.retryTimer = m.top.findNode("retryTimer")
    m.clockTimer = m.top.findNode("clockTimer")

    m.video.observeField("state", "onVideoState")
    m.miniList.observeField("itemSelected", "onMiniSelected")
    m.infoLogo.observeField("loadStatus", "onLogoStatus")
    m.infoTimer.observeField("fire", "hideInfo")
    m.zapTimer.observeField("fire", "startPlayback")
    m.retryTimer.observeField("fire", "playCandidate")
    m.clockTimer.observeField("fire", "onClockTick")
    m.top.observeField("params", "onParams")
    m.top.observeField("active", "onActive")
    m.top.observeField("closed", "onClosed")
    ' When the guide finishes loading (or refreshes), re-fetch now/next for the overlay.
    m.global.bus.observeFieldScoped("epgVersion", "onEpgChanged")

    m.content = invalid
    m.channel = invalid
    m.index = -1
    m.prevIndex = -1
    m.playingKey = ""
    m.candidates = []
    m.cand = 0
    m.retries = 0
    m.nowNow = invalid
    m.nowNext = invalid
    m.listReply = invalid
    m.nowNextReply = invalid
    m.category = "__all__"
    m.wantKey = ""
    m.clockTimer.control = "start"
end sub

sub onParams()
    p = m.top.params
    if p.category <> invalid then m.category = p.category
    if p.key <> invalid then m.wantKey = p.key
    if p.content <> invalid then
        setList(p.content)
    else
        m.loading.visible = true
        m.listReply = svcCall({ type: "list", category: m.category }, "onList")
    end if
end sub

sub onActive()
    if not m.top.active then return
    if m.mini.visible then
        m.miniList.setFocus(true)
    else
        m.top.setFocus(true)
    end if
end sub

sub onEpgChanged()
    if m.channel <> invalid then m.nowNextReply = svcCall({ type: "nowNext", key: m.channel.id }, "onNowNext")
end sub

sub onClosed()
    m.global.bus.unobserveFieldScoped("epgVersion")
    m.video.control = "stop"
    m.infoTimer.control = "stop"
    m.zapTimer.control = "stop"
    m.retryTimer.control = "stop"
    m.clockTimer.control = "stop"
end sub

sub onList(event as object)
    if m.listReply = invalid or not event.getRoSGNode().isSameNode(m.listReply) then return
    m.listReply = invalid
    content = listToContent(event.getData().items)
    if (content = invalid or content.getChildCount() = 0) and m.category <> "__all__" then
        m.category = "__all__"
        m.listReply = svcCall({ type: "list", category: m.category }, "onList")
        return
    end if
    setList(content)
end sub

sub setList(content as dynamic)
    m.content = content
    if content = invalid or content.getChildCount() = 0 then
        m.loading.visible = false
        showError("No channels", "This category is empty.")
        return
    end if
    idx = 0
    for i = 0 to content.getChildCount() - 1
        if content.getChild(i).id = m.wantKey then
            idx = i
            exit for
        end if
    end for
    m.miniList.content = content
    m.miniTitle.text = categoryLabel(m.category)
    tune(idx, true)
end sub

' ---------------------------------------------------------------- tuning

sub tune(index as integer, immediate as boolean)
    if m.content = invalid then return
    n = m.content.getChildCount()
    if n = 0 then return
    index = ((index mod n) + n) mod n
    if index <> m.index and m.index >= 0 then m.prevIndex = m.index
    m.index = index
    m.channel = m.content.getChild(index)
    m.nowNow = invalid
    updateInfo()
    showInfo()
    m.nowNextReply = svcCall({ type: "nowNext", key: m.channel.id }, "onNowNext")
    m.zapTimer.control = "stop"
    if immediate then
        startPlayback()
    else
        ' Debounced so holding Up/Down skims channels without starting every stream.
        m.zapTimer.control = "start"
    end if
end sub

sub startPlayback()
    ch = m.channel
    if ch = invalid then return
    if ch.id = m.playingKey and m.video.state = "playing" then return
    m.playingKey = ch.id
    m.candidates = streamCandidates(ch.url)
    m.cand = 0
    m.retries = 0
    m.errorBox.visible = false
    playCandidate()
    regWrite("lastKey", ch.id)
    regWrite("lastCategory", m.category)
end sub

sub playCandidate()
    if m.channel = invalid or m.cand >= m.candidates.Count() then return
    c = m.candidates[m.cand]
    print "CoxTV: play "; m.channel.title; " ["; c.format; "] "; c.url
    vc = CreateObject("roSGNode", "ContentNode")
    vc.setFields({ url: c.url, streamFormat: c.format, title: m.channel.title, live: true })
    m.loading.visible = true
    m.video.control = "stop"
    m.video.content = vc
    m.video.control = "play"
end sub

sub onVideoState()
    state = m.video.state
    if state = "playing" then
        m.loading.visible = false
        m.errorBox.visible = false
        m.retries = 0
    else if state = "buffering" then
        m.loading.visible = true
    else if state = "error" then
        print "CoxTV: playback error "; m.video.errorCode; " "; m.video.errorMsg
        if m.cand + 1 < m.candidates.Count() then
            m.cand = m.cand + 1 ' fall back, e.g. from the .m3u8 guess to the original .ts URL
            playCandidate()
        else
            m.loading.visible = false
            showError("Can't play " + m.channel.title, m.video.errorMsg + " (error " + m.video.errorCode.ToStr() + ")")
        end if
    else if state = "finished" then
        ' Live streams should not finish; reconnect a few times.
        if m.retries < 3 then
            m.retries = m.retries + 1
            m.retryTimer.control = "start"
        end if
    end if
end sub

sub showError(title as string, msg as string)
    m.errorTitle.text = title
    m.errorMsg.text = msg
    m.errorBox.visible = true
end sub

' ---------------------------------------------------------------- overlay

sub showInfo()
    m.info.visible = true
    m.infoTimer.control = "stop"
    m.infoTimer.control = "start"
end sub

sub hideInfo()
    m.info.visible = false
    m.infoTimer.control = "stop"
end sub

sub onClockTick()
    m.infoClock.text = fmtClock(nowSecs())
    if m.info.visible then renderProgram()
end sub

sub updateInfo()
    ch = m.channel
    m.infoNum.text = ch.shortDescriptionLine2
    m.infoName.text = ch.title
    m.infoInitials.text = initials(ch.title)
    m.infoLogo.uri = ch.hdPosterUrl
    onLogoStatus()
    m.infoClock.text = fmtClock(nowSecs())
    setFavVisual(ch.starRating >= 100)
    ' Provisional now-playing from the list until the nowNext reply arrives.
    if ch.description <> "" then
        m.nowNow = [ch.playStart, ch.length, ch.description, ""]
    end if
    m.nowNext = invalid
    renderProgram()
end sub

sub setFavVisual(isFav as boolean)
    m.infoFav.visible = isFav
    if isFav then
        m.infoName.translation = [412, 734]
    else
        m.infoName.translation = [360, 734]
    end if
end sub

sub onLogoStatus()
    m.infoInitials.visible = (m.infoLogo.uri = "" or m.infoLogo.loadStatus = "failed")
end sub

sub onNowNext(event as object)
    if m.nowNextReply = invalid or not event.getRoSGNode().isSameNode(m.nowNextReply) then return
    res = event.getData()
    m.nowNow = res.now
    m.nowNext = res.next
    renderProgram()
end sub

sub renderProgram()
    p = m.nowNow
    if p = invalid then
        m.infoTitle.text = "No program information"
        m.infoTime.text = ""
        m.infoLeft.text = ""
        m.infoDesc.text = ""
        m.infoBar.visible = false
        m.infoBarBg.visible = false
    else
        m.infoTitle.text = p[2]
        m.infoTime.text = fmtRange(p[0], p[1])
        m.infoLeft.text = minsLeftText(p[1])
        m.infoDesc.text = p[3]
        frac = 0
        if p[1] > p[0] then frac = (nowSecs() - p[0]) / (p[1] - p[0])
        if frac < 0 then frac = 0
        if frac > 1 then frac = 1
        m.infoBar.width = 600 * frac
        m.infoBar.visible = true
        m.infoBarBg.visible = true
    end if
    if m.nowNext <> invalid then
        m.infoNext.text = "Next   " + fmtClock(m.nowNext[0]) + "   " + m.nowNext[2]
    else
        m.infoNext.text = ""
    end if
end sub

' ---------------------------------------------------------------- mini guide

sub openMini()
    if m.content = invalid then return
    hideInfo()
    m.mini.visible = true
    if m.index >= 0 then m.miniList.jumpToItem = m.index
    m.miniList.setFocus(true)
end sub

sub closeMini()
    m.mini.visible = false
    m.top.setFocus(true)
end sub

sub onMiniSelected()
    idx = m.miniList.itemSelected
    closeMini()
    tune(idx, true)
end sub

sub toggleFav(ch as dynamic)
    if ch = invalid then return
    isFav = toggleFavorite(ch.id)
    if isFav then
        ch.starRating = 100
    else
        ch.starRating = 0
    end if
    if m.channel <> invalid and ch.isSameNode(m.channel) then
        setFavVisual(isFav)
        if not m.mini.visible then showInfo()
    end if
end sub

function onKeyEvent(key as string, press as boolean) as boolean
    if not press then return false

    if m.mini.visible then
        if key = "back" or key = "right" or key = "left" then
            closeMini()
        else if key = "options" then
            toggleFav(m.content.getChild(m.miniList.itemFocused))
        end if
        return true
    end if

    if key = "up" or key = "channelup" then
        tune(m.index + 1, false)
    else if key = "down" or key = "channeldown" then
        tune(m.index - 1, false)
    else if key = "OK" then
        if m.info.visible then
            hideInfo()
        else
            showInfo()
        end if
    else if key = "left" then
        openMini()
    else if key = "right" or key = "info" then
        showInfo()
    else if key = "options" then
        toggleFav(m.channel)
    else if key = "replay" then
        if m.prevIndex >= 0 then tune(m.prevIndex, true)
    else if key = "play" then
        if m.video.state = "paused" then
            m.video.control = "resume"
        else if m.video.state = "playing" then
            m.video.control = "pause"
        end if
    else if key = "back" then
        if m.info.visible then
            hideInfo()
            return true
        end if
        return false
    end if
    return true
end function
