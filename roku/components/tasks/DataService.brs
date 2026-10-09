sub init()
    m.top.functionName = "serviceLoop"
end sub

sub serviceLoop()
    m.port = CreateObject("roMessagePort")
    ' All shared state lives on m.global.bus, a node owned by the render thread: the UI
    ' reads it locally and never has to wait on this (often busy) task thread.
    m.bus = m.global.bus
    m.bus.observeField("request", m.port)
    m.pl = newPlaylist()
    m.epg = {}
    m.epgUrl = ""
    m.epgLoadedAt = 0
    m.m3uUrl = ""
    m.refreshPlaylistDue = false
    m.nowIdx = {}
    m.nowIdxUntil = 0
    m.deferred = invalid
    m.bus.ready = true

    m.lastSyncCheck = 0
    m.syncSnapshot = ""
    m.lastSync = 0
    while true
        msg = wait(30000, m.port)
        maybeSyncPrefs()
        if msg = invalid then
            ensureNowIndex(nowSecs()) ' keep search instant: rebuild while idle
            ' Housekeeping while the app is open: refresh the guide every 4 hours, and a
            ' channel list that was restored from an old cache.
            if m.refreshPlaylistDue then
                m.refreshPlaylistDue = false
                refreshPlaylist()
            else if m.epgUrl <> "" and m.epgLoadedAt > 0 and nowSecs() - m.epgLoadedAt > 4 * 3600 and streamSettled() then
                loadEpg(m.epgUrl, true)
            end if
        else if type(msg) = "roSGNodeEvent" then
            handleRequest(msg.getData())
        end if
        while m.deferred <> invalid
            req = m.deferred
            m.deferred = invalid
            handleRequest(req)
        end while
    end while
end sub

' All channel data in one object (parallel arrays + indexes) so it can be saved/restored
' as a single JSON file and swapped in one step after a background refresh.
function newPlaylist() as object
    return {
        names: []         ' display names
        nameBlob: ""      ' all lower-case names joined by newlines, for native Instr search
        nameOffs: []      ' 1-based start of each name in nameBlob
        urls: []
        logos: []
        tvg: []           ' tvg-id per channel
        grp: []           ' group index per channel
        keys: []          ' stable short key per channel (favorites / last watched)
        nums: []          ' channel number text
        groupNames: []
        groupIndex: {}    ' group name -> index
        groupMembers: []  ' group index -> [channel indexes]
        keyIndex: {}      ' key -> channel index
        tvgIndex: {}      ' tvg-id -> [channel indexes]
        detectedEpg: ""
    }
end function

sub handleRequest(req as dynamic)
    if req = invalid or req.type = invalid then return
    t = req.type
    if t = "load" then
        loadAll(req)
        return
    end if

    result = invalid
    if t = "list" then
        result = buildList(req.category, req.start, req.count)
    else if t = "now" then
        result = { now: nowFor(req.keys) }
    else if t = "window" then
        result = { programs: windowFor(req.keys, req.startTime, req.endTime) }
    else if t = "nowNext" then
        result = nowNext(req.key)
    else if t = "search" then
        result = { query: req.query, results: search(req.query) }
    else if t = "sources" then
        result = { key: req.key, urls: sourcesFor(req.key) }
    else if t = "hiddenList" then
        result = { items: hiddenList() }
    else if t = "setupCode" then
        result = redeemSetupCode(req.code)
    else if t = "serverKey" then
        result = { key: serverKey(req.id) }
    else if t = "sameGame" then
        result = sameGame(req.key, req.url)
    else if t = "cfbTeams" then
        result = { teams: cfbTeams(req.index) }
    else if t = "teamCatalog" then
        if m.teamCatalog = invalid then
            got = deviceGet("/teams")
            if got <> invalid and type(got.sports) = "roArray" then m.teamCatalog = got.sports
        end if
        result = { sports: m.teamCatalog }
    else if t = "syncNow" then
        m.cfbUntil = 0
        syncPrefs()
        result = { ok: true }
    end if
    if result <> invalid and req.reply <> invalid then req.reply.result = result
end sub

' Waits up to ms milliseconds, answering UI requests as they arrive.
sub serveRequestsFor(ms as integer)
    t = CreateObject("roTimespan")
    while t.TotalMilliseconds() < ms
        msg = wait(50, m.port)
        if msg <> invalid and type(msg) = "roSGNodeEvent" then
            req = msg.getData()
            if req <> invalid and req.type = "load" then
                m.deferred = req
            else
                handleRequest(req)
            end if
        end if
    end while
end sub

' Answer queued UI requests while a long download/parse runs, so screens stay responsive.
sub pumpRequests()
    while true
        msg = m.port.GetMessage()
        if msg = invalid then return
        if type(msg) = "roSGNodeEvent" then
            req = msg.getData()
            if req <> invalid and req.type = "load" then
                m.deferred = req
            else
                handleRequest(req)
            end if
        end if
    end while
end sub

' ---------------------------------------------------------------- loading

sub loadAll(req as object)
    m.bus.playlistState = "loading"
    m.bus.epgState = "idle"
    m.epg = {}
    m.nowIdxUntil = 0
    m.epgUrl = ""
    m.epgLoadedAt = 0
    m.refreshPlaylistDue = false
    url = req.m3u
    if url = invalid or url = "" then
        m.bus.status = "No playlist URL set"
        m.bus.playlistState = "error"
        return
    end if
    m.m3uUrl = url

    ' Fast path: restore the parsed channel list saved by a previous launch.
    cacheAge = loadChannelCache(url)
    if cacheAge >= 0 then
        m.refreshPlaylistDue = cacheAge > 12 * 3600
    else
        pl = parsePlaylistFrom(url)
        if pl = invalid then
            m.bus.playlistState = "error"
            return
        end if
        m.pl = pl
        saveChannelCache(url)
    end if
    publishPlaylist()
    serveRequestsFor(300) ' let the UI's first channel-list request in before the guide work

    epg = req.epg
    if epg = invalid or epg = "" then epg = m.pl.detectedEpg
    m.epgUrl = epg
    if epg = "" then
        m.bus.epgState = "none"
        return
    end if
    ' The guide is the heaviest work in the app (a big download plus parsing). When the app
    ' reopens straight into the last channel, let that stream start and settle first.
    m.bus.epgState = "loading"
    expectPlayer = false
    if type(req.resume) = "roBoolean" or type(req.resume) = "Boolean" then expectPlayer = req.resume
    print "CoxTV: guide waits for playback to settle (resume="; expectPlayer; ")"
    waitForStreamToSettle(45, expectPlayer)
    print "CoxTV: guide work starts"
    if not loadGuideCache(epg) then
        loadEpg(epg, false)
    end if
end sub

' True when nothing is playing, or the stream has been playing for a few seconds - i.e.
' heavy guide work now won't hold up a channel that is starting.
function streamSettled() as boolean
    if not m.bus.playerOpen then return true
    since = m.bus.playingSince
    return since > 0 and nowSecs() - since >= 10
end function

' Answers UI requests until streamSettled(), for at most maxSecs. With expectPlayer (the app
' is reopening into the last channel) it first gives the player a moment to open.
sub waitForStreamToSettle(maxSecs as integer, expectPlayer as boolean)
    t = CreateObject("roTimespan")
    while expectPlayer and not m.bus.playerOpen and t.TotalSeconds() < 3
        serveRequestsFor(100)
    end while
    while not streamSettled() and t.TotalSeconds() < maxSecs
        serveRequestsFor(250)
    end while
end sub

sub publishPlaylist()
    cats = []
    for i = 0 to m.pl.groupNames.Count() - 1
        cats.Push({ name: m.pl.groupNames[i], count: m.pl.groupMembers[i].Count() })
    end for
    resetListCaches()
    m.bus.channelCount = allShown().Count() ' after folding copies and hiding
    m.bus.categories = cats
    m.bus.status = ""
    m.bus.playlistState = "ready"
end sub

' Background refresh of a channel list restored from an old cache: parse into a new
' object while the old one keeps serving the UI, then swap.
sub refreshPlaylist()
    if m.m3uUrl = "" then return
    pl = parsePlaylistFrom(m.m3uUrl)
    if pl = invalid then return
    m.pl = pl
    saveChannelCache(m.m3uUrl)
    m.nowIdxUntil = 0
    publishPlaylist()
end sub

' Downloads and parses an M3U into a new playlist object (invalid on failure).
function parsePlaylistFrom(url as string) as dynamic
    ' Linked: the stream server's ready-made list, when it was built from this playlist.
    fromServer = serverPlaylist(url)
    if fromServer <> invalid then return fromServer
    path = "cachefs:/playlist.m3u"
    m.bus.status = "Downloading playlist..."
    if not downloadToFile(url, path) then
        m.bus.status = "Could not download playlist: " + m.lastHttpError
        return invalid
    end if

    m.bus.status = "Loading channels..."
    pl = newPlaylist()
    m.pending = invalid
    m.pendingGroup = ""
    m.digest = CreateObject("roEVPDigest")
    m.keyBytes = CreateObject("roByteArray")

    reader = newChunkReader(path)
    carry = ""
    while true
        text = reader.next()
        if text = invalid then exit while
        lines = (carry + text).Split(chr(10))
        carry = lines.Pop() ' last line may continue in the next chunk
        for each line in lines
            processM3uLine(line, pl)
        end for
        m.bus.status = "Loading channels... " + pl.names.Count().ToStr()
        pumpRequests()
    end while
    if carry <> invalid and carry <> "" then processM3uLine(carry, pl)
    m.digest = invalid
    m.keyBytes = invalid
    CreateObject("roFileSystem").Delete(path) ' the JSON cache replaces it

    if pl.names.Count() = 0 then
        m.bus.status = "No channels found in the playlist"
        return invalid
    end if
    buildNameIndex(pl)
    m.bus.status = "Combining duplicate channels..."
    buildGroups(pl)
    return pl
end function

sub processM3uLine(raw as string, pl as object)
    ' Hot loop (~175k lines for a big provider playlist): keep it cheap.
    line = raw
    if Right(line, 1) = chr(13) then line = Left(line, Len(line) - 1)
    if line = "" then return
    if Left(line, 1) = "#" then
        if Left(line, 8) = "#EXTINF:" then
            m.pending = line
            m.pendingGroup = ""
        else if Left(line, 8) = "#EXTGRP:" then
            m.pendingGroup = Mid(line, 9).Trim()
        else if Left(line, 7) = "#EXTM3U" then
            u = attrOf(line, "url-tvg")
            if u = "" then u = attrOf(line, "x-tvg-url")
            if u <> "" then pl.detectedEpg = u.Split(",")[0].Trim()
        end if
        return
    end if
    if m.pending = invalid then return
    ' Live TV only: provider "m3u_plus" playlists also list every movie and series
    ' episode (often 3-4x the live channels). Skip them before any attribute parsing.
    if Instr(1, line, "/movie/") = 0 and Instr(1, line, "/series/") = 0 then addChannel(m.pending, line.Trim(), pl)
    m.pending = invalid
end sub

sub addChannel(info as string, url as string, pl as object)
    key = channelKey(url)
    if pl.keyIndex.DoesExist(key) then return

    name = extinfName(info)
    if name = "" then name = attrOf(info, "tvg-name")
    idx = pl.names.Count()
    if name = "" then name = "Channel " + (idx + 1).ToStr()

    group = attrOf(info, "group-title")
    if group = "" then group = m.pendingGroup
    if group = "" then group = "Uncategorized"
    gi = pl.groupIndex[group]
    if gi = invalid then
        gi = pl.groupNames.Count()
        pl.groupIndex[group] = gi
        pl.groupNames.Push(group)
        pl.groupMembers.Push([])
    end if

    num = attrOf(info, "tvg-chno")
    if num = "" then num = (idx + 1).ToStr()
    tvgId = attrOf(info, "tvg-id")

    pl.keyIndex[key] = idx
    pl.names.Push(name)
    pl.urls.Push(url)
    pl.logos.Push(attrOf(info, "tvg-logo"))
    pl.tvg.Push(tvgId)
    pl.grp.Push(gi)
    pl.keys.Push(key)
    pl.nums.Push(num)
    pl.groupMembers[gi].Push(idx)
    if tvgId <> "" then
        lst = pl.tvgIndex[tvgId]
        if lst = invalid then
            pl.tvgIndex[tvgId] = [idx]
        else
            lst.Push(idx)
        end if
    end if
end sub

' Stable short key for favorites / last watched. Xtream URLs end in a numeric stream id,
' which is unique and free to extract; anything else gets a truncated MD5 of the URL.
function channelKey(url as string) as string
    q = Instr(1, url, "?")
    if q > 0 then
        base = Left(url, q - 1)
    else
        base = url
    end if
    p = 0
    s = Instr(1, base, "/")
    while s > 0
        p = s
        s = Instr(s + 1, base, "/")
    end while
    id = Mid(base, p + 1)
    dot = Instr(1, id, ".")
    if dot > 0 then id = Left(id, dot - 1)
    if Len(id) > 0 and Len(id) < 10 and id.ToInt().ToStr() = id then return "x" + id
    m.keyBytes.FromAsciiString(url)
    m.digest.Setup("md5")
    return Left(m.digest.Process(m.keyBytes), 12)
end function

' ---------------------------------------------------------------- caches (JSON)

' Returns the cache age in seconds, or -1 if there is no usable cache for this URL.
function loadChannelCache(url as string) as integer
    path = "cachefs:/channels.json"
    if regRead("chCacheUrl") <> url or not fileExists(path) then return -1
    m.bus.status = "Loading channels..."
    data = ParseJson(ReadAsciiFile(path), "i")
    if data = invalid or data.names = invalid or data.names.Count() = 0 or data.nameOffs = invalid then return -1
    m.pl = data
    if data.shown = invalid then
        ' Cache from before duplicate channels were combined: group once and re-save.
        buildGroups(data)
        saveChannelCache(url)
    end if
    return nowSecs() - regRead("chCacheAt", "0").ToInt()
end function

sub saveChannelCache(url as string)
    json = FormatJson(m.pl)
    if json <> "" and WriteAsciiFile("cachefs:/channels.json", json) then
        regWrite("chCacheUrl", url)
        regWrite("chCacheAt", nowSecs().ToStr())
    end if
end sub

' Restores the parsed guide saved by an earlier launch (up to a day old: an older guide
' still fills in what it can right away). Anything older than 4 hours is then refreshed
' in the background by the service loop, once playback has settled. Returns false if
' there is none.
function loadGuideCache(url as string) as boolean
    path = "cachefs:/guide.json"
    cachedAt = regRead("gCacheAt", "0").ToInt()
    if regRead("gCacheUrl") <> url or nowSecs() - cachedAt > 24 * 3600 or not fileExists(path) then return false
    m.bus.epgState = "loading"
    m.bus.status = "Loading guide..."
    data = ParseJson(ReadAsciiFile(path), "i")
    if data = invalid then return false
    m.epg = data
    m.nowIdxUntil = 0
    m.epgLoadedAt = cachedAt
    m.bus.status = ""
    m.bus.epgVersion = m.bus.epgVersion + 1
    m.bus.epgState = "ready"
    return true
end function

sub saveGuideCache(url as string)
    json = FormatJson(m.epg)
    if json <> "" and WriteAsciiFile("cachefs:/guide.json", json) then
        regWrite("gCacheUrl", url)
        regWrite("gCacheAt", m.epgLoadedAt.ToStr())
    end if
end sub

sub loadEpg(url as string, isRefresh as boolean)
    if not isRefresh then m.bus.epgState = "loading"
    path = "cachefs:/epg.xml"
    m.bus.status = "Downloading guide..."
    ' A guide we already have (ready-made guides send an ETag) is not downloaded again.
    etag = ""
    if isRefresh and regRead("gCacheUrl") = url then etag = regRead("gEtag")
    if not downloadToFile(url, path, etag) then
        if m.notModified then
            m.bus.status = ""
            m.epgLoadedAt = nowSecs()
            regWrite("gCacheAt", m.epgLoadedAt.ToStr())
            return
        end if
        m.bus.status = "Guide download failed: " + m.lastHttpError
        m.epgLoadedAt = nowSecs() ' retry at the next refresh interval
        if not isRefresh then m.bus.epgState = "error"
        return
    end if
    m.bus.status = "Loading guide..."
    prebuilt = readPrebuiltGuide(path)
    if prebuilt <> invalid then
        m.epg = prebuilt
    else
        m.epg = parseEpgFile(path)
    end if
    CreateObject("roFileSystem").Delete(path) ' the JSON cache replaces it
    m.epgLoadedAt = nowSecs()
    m.nowIdxUntil = 0
    m.bus.status = ""
    m.bus.epgVersion = m.bus.epgVersion + 1
    m.bus.epgState = "ready"
    saveGuideCache(url)
    regWrite("gEtag", m.lastEtag)
end sub

' A guide already in this app's format, e.g. from the CoxOnAir stream server
' ({"coxtv_guide": 1, "programs": {tvg-id: [[start, end, title, desc], ...]}}).
' Loads in about a second, where parsing a big provider XMLTV takes minutes on a Roku.
' Returns invalid for anything else (normal XMLTV).
function readPrebuiltGuide(path as string) as dynamic
    ba = CreateObject("roByteArray")
    ba.ReadFile(path, 0, 16)
    head = ba.ToAsciiString().Trim()
    if Left(head, 1) <> "{" then return invalid
    data = ParseJson(ReadAsciiFile(path))
    if type(data) <> "roAssociativeArray" or data.coxtv_guide = invalid or type(data.programs) <> "roAssociativeArray" then return invalid
    print "CoxTV: ready-made guide for "; data.programs.Count(); " channels"
    return data.programs
end function

' Streams through the XMLTV file chunk by chunk with Instr scanning (no DOM), keeping
' only programmes for playlist channels within the guide window.
function parseEpgFile(path as string) as object
    epg = {}
    now = nowSecs()
    ' Keep -1h..+9h (the guide refreshes every 4h, so it always reaches >= 5h ahead).
    ' Big providers ship ~2 days for thousands of channels; this bounds memory.
    minEnd = now - 3600
    maxStart = now + 9 * 3600
    maxPrograms = 80000
    boundOff = invalid
    maxStartStr = ""
    minEndStr = ""
    count = 0
    carry = ""
    tvgIndex = m.pl.tvgIndex
    reader = newChunkReader(path)
    while true
        chunk = reader.next()
        if chunk = invalid then exit while
        text = carry + chunk
        p = 1
        carryFrom = 0
        while true
            a = Instr(p, text, "<programme")
            if a = 0 then exit while
            tagEnd = Instr(a, text, ">")
            if tagEnd = 0 then
                carryFrom = a
                exit while
            end if
            ' Decide from the short start tag alone; most programmes are skipped
            ' (other channels / outside the time window) without touching the body.
            tag = Mid(text, a, tagEnd - a + 1)
            p = tagEnd + 1
            ch = attrOf(tag, "channel")
            if ch <> "" and count < maxPrograms and tvgIndex.DoesExist(ch) then
                ' Cheap window check by comparing "YYYYMMDDhhmmss" text in the file's own
                ' UTC offset; full date math only for programmes that pass.
                startStr = attrOf(tag, "start")
                off = Mid(startStr, 16, 5)
                if boundOff = invalid or off <> boundOff then
                    boundOff = off
                    offSecs = xmltvTime("19700101000000 " + off) * -1
                    maxStartStr = epochToStamp(maxStart + offSecs)
                    minEndStr = epochToStamp(minEnd + offSecs)
                end if
                if Left(startStr, 14) < maxStartStr then
                    stopStr = attrOf(tag, "stop")
                    s = 0
                    e = 0
                    if Left(stopStr, 14) > minEndStr then
                        s = xmltvTime(startStr)
                        e = xmltvTime(stopStr)
                    end if
                    if e > minEnd and s < maxStart and e > s then
                        b = Instr(tagEnd, text, "</programme>")
                        if b = 0 then
                            carryFrom = a
                            exit while
                        end if
                        body = Mid(text, tagEnd + 1, b - tagEnd - 1)
                        lst = epg[ch]
                        if lst = invalid then
                            lst = []
                            epg[ch] = lst
                        end if
                        lst.Push([s, e, cleanTitle(xmlDecode(tagText(body, "title"))), xmlDecode(Left(tagText(body, "desc"), 160))])
                        count = count + 1
                        p = b + 12
                    end if
                end if
            end if
        end while
        if carryFrom > 0 then
            carry = Mid(text, carryFrom)
        else
            keep = Len(text) - 15
            if keep < p then keep = p
            carry = Mid(text, keep)
        end if
        m.bus.status = "Loading guide... " + count.ToStr() + " programs"
        pumpRequests()
        ' A channel is starting: pause parsing so it gets the CPU (resumes once it plays).
        if not streamSettled() then waitForStreamToSettle(20, false)
    end while
    for each id in epg
        sortPrograms(epg[id])
    end for
    return epg
end function

' XMLTV is normally already in order per channel, so this insertion sort is ~O(n).
sub sortPrograms(lst as object)
    for i = 1 to lst.Count() - 1
        item = lst[i]
        j = i - 1
        while j >= 0 and lst[j][0] > item[0]
            lst[j + 1] = lst[j]
            j = j - 1
        end while
        lst[j + 1] = item
    end for
end sub

function downloadToFile(url as string, path as string, etag = "" as string) as boolean
    m.lastHttpError = ""
    m.notModified = false
    m.lastEtag = ""
    ut = CreateObject("roUrlTransfer")
    port = CreateObject("roMessagePort")
    ut.SetMessagePort(port)
    ut.SetUrl(url)
    ut.EnableEncodings(true)
    if etag <> "" then ut.AddHeader("If-None-Match", etag)
    if LCase(Left(url, 6)) = "https:" then
        ut.SetCertificatesFile("common:/certs/ca-bundle.crt")
        ut.InitClientCertificates()
    end if
    tmp = path + ".part"
    if not ut.AsyncGetToFile(tmp) then
        m.lastHttpError = "request could not start"
        return false
    end if
    idleMs = 0
    while true
        ' Short waits so UI requests (channel lists, search) are answered while a
        ' large playlist/guide downloads.
        msg = wait(250, port)
        if msg = invalid then
            pumpRequests()
            idleMs = idleMs + 250
            if idleMs >= 900000 then
                ut.AsyncCancel()
                m.lastHttpError = "timed out"
                return false
            end if
        else if type(msg) = "roUrlEvent" and msg.GetInt() = 1 then
            code = msg.GetResponseCode()
            if code = 304 then
                m.notModified = true ' the copy we have is current
                return false
            end if
            headers = msg.GetResponseHeaders()
            if headers <> invalid then
                if headers.etag <> invalid then m.lastEtag = headers.etag
                if headers.ETag <> invalid then m.lastEtag = headers.ETag
            end if
            if code >= 200 and code < 300 then
                fs = CreateObject("roFileSystem")
                if not fs.Exists(tmp) then
                    m.lastHttpError = "downloaded but could not be saved (device storage full?)"
                    return false
                end if
                if fs.Exists(path) then fs.Delete(path)
                if fs.Rename(tmp, path) then return true
                m.lastHttpError = "could not save the file (device storage full?)"
                return false
            end if
            m.lastHttpError = "HTTP " + code.ToStr() + " " + msg.GetFailureReason()
            return false
        end if
    end while
    return false
end function

function fileExists(path as string) as boolean
    return CreateObject("roFileSystem").Exists(path)
end function

' ---------------------------------------------------------------- queries

' One page [start, start+count) of a category's channels as compact rows (see
' appendContentRows in Utils.brs). Paging keeps the first screen of a 5,000-channel
' list fast: the UI asks for ~200 rows, then fetches the rest in the background.
function buildList(category as dynamic, start as dynamic, count as dynamic) as object
    pl = m.pl
    idxs = invalid
    labels = invalid
    if category = "__fav__" then
        idxs = shownFor(favKeys())
    else if category = "__recent__" then
        idxs = shownFor(recentKeys())
    else if category = "__sports__" then
        idxs = []
        labels = []
        for each g in sportsList()
            idxs.Push(g.i)
            labels.Push(g)
        end for
    else if category = "__cfb__" then
        items = cfbList()
        return { category: category, items: items, start: 0, listTotal: items.Count(), total: allShown().Count() }
    else if Left(category, 11) = "__league__:" then
        league = Mid(category, 12)
        idxs = []
        labels = []
        for each g in sportsList()
            if g.league = league then
                idxs.Push(g.i)
                labels.Push(g)
            end if
        end for
    else if category = "__all__" or category = invalid then
        idxs = allShown()
    else
        gi = pl.groupIndex[category]
        if gi <> invalid then
            idxs = groupShown(category, pl.groupMembers[gi])
        else
            idxs = []
        end if
    end if
    if labels <> invalid then
        ' Games: favorite teams' games first (starred), keeping the league order otherwise.
        teams = favoriteTeams()
        mineIdx = []
        mineLab = []
        restIdx = []
        restLab = []
        for j = 0 to labels.Count() - 1
            g = labels[j]
            g.mine = isMyTeamGame(g.title, g.league, teams)
            if g.mine then
                mineIdx.Push(idxs[j])
                mineLab.Push(g)
            else
                restIdx.Push(idxs[j])
                restLab.Push(g)
            end if
        end for
        mineIdx.Append(restIdx)
        mineLab.Append(restLab)
        idxs = mineIdx
        labels = mineLab
    end if
    listTotal = idxs.Count()
    if category = "__all__" and listTotal > 5000 then listTotal = 5000 ' keep "All" usable on huge playlists

    s = 0
    if start <> invalid then s = start
    e = listTotal
    if count <> invalid and s + count < e then e = s + count

    favs = {}
    for each i in shownFor(favKeys())
        favs[i.ToStr()] = true
    end for
    now = nowSecs()
    ' Plain arrays, not ContentNodes: nodes created here would be owned by this thread
    ' and every UI read would have to rendezvous with it.
    items = []
    for j = s to e - 1
        i = idxs[j]
        nowTitle = ""
        nowStart = 0
        nowEnd = 0
        p = currentProgram(pl.tvg[i], now)
        if p <> invalid then
            nowTitle = p[2]
            nowStart = p[0]
            nowEnd = p[1]
        end if
        if labels <> invalid then
            nowTitle = labels[j].league + "  -  " + labels[j].title
            if labels[j].mine = true then nowTitle = "Your team  -  " + nowTitle
        end if
        items.Push([pl.keys[i], pl.names[i], pl.urls[i], pl.logos[i], pl.groupNames[pl.grp[i]], pl.nums[i], favs.DoesExist(i.ToStr()), nowTitle, nowStart, nowEnd])
    end for
    return { category: category, items: items, start: s, listTotal: listTotal, total: allShown().Count() }
end function

' ---------------------------------------------------------------- shown / hidden channels

sub resetListCaches()
    m.hiddenRaw = invalid
    m.hiddenSet = invalid
    m.allShownCache = invalid
    m.groupCache = {}
    m.sportsCache = invalid
    m.serverIdx = invalid
    m.srvSports = invalid
    m.cfb = invalid
end sub

' Key of the shown channel for a stream server channel id (a channel sent from the remote).
function serverKey(id as dynamic) as string
    if id = invalid or id = "" then return ""
    pl = m.pl
    if m.serverIdx = invalid then
        idx = {}
        for i = 0 to pl.urls.Count() - 1
            sid = serverIdOf(pl.urls[i])
            if sid <> "" and not idx.DoesExist(sid) then idx[sid] = i
        end for
        m.serverIdx = idx
    end if
    i = m.serverIdx[id]
    if i = invalid then return ""
    return pl.keys[pl.shown[i]]
end function

' Shown-channel indexes the user hid ("hidden" registry keys; hiding any copy hides all).
function hiddenSet() as object
    raw = regRead("hidden")
    if m.hiddenSet <> invalid and raw = m.hiddenRaw then return m.hiddenSet
    pl = m.pl
    set = {}
    for each k in raw.Split(",")
        i = pl.keyIndex[k]
        if i <> invalid then set[pl.shown[i].ToStr()] = true
    end for
    m.hiddenRaw = raw
    m.hiddenSet = set
    m.allShownCache = invalid
    m.groupCache = {}
    m.sportsCache = invalid
    return set
end function

' Every shown channel (one per group of copies), not hidden, in playlist order.
function allShown() as object
    hidden = hiddenSet()
    if m.allShownCache <> invalid then return m.allShownCache
    pl = m.pl
    out = []
    shown = pl.shown
    for i = 0 to pl.names.Count() - 1
        if shown[i] = i and not hidden.DoesExist(i.ToStr()) then out.Push(i)
        if i mod 4000 = 3999 then pumpRequests()
    end for
    m.allShownCache = out
    return out
end function

' A playlist group's channels as shown channels (a copy in this group shows its group's channel).
function groupShown(name as string, members as object) as object
    hidden = hiddenSet()
    if m.groupCache = invalid then m.groupCache = {}
    cached = m.groupCache[name]
    if cached <> invalid then return cached
    out = []
    seen = {}
    for each i in members
        s = m.pl.shown[i]
        k = s.ToStr()
        if not seen.DoesExist(k) and not hidden.DoesExist(k) then
            seen[k] = true
            out.Push(s)
        end if
    end for
    m.groupCache[name] = out
    return out
end function

' Channel keys -> shown channel indexes (deduplicated, hidden ones left out), in order.
function shownFor(keys as object) as object
    pl = m.pl
    hidden = hiddenSet()
    out = []
    seen = {}
    for each k in keys
        i = pl.keyIndex[k]
        if i <> invalid then
            s = pl.shown[i]
            sk = s.ToStr()
            if not seen.DoesExist(sk) and not hidden.DoesExist(sk) then
                seen[sk] = true
                out.Push(s)
            end if
        end if
    end for
    return out
end function

' Stream URLs of a channel's other copies, best first (for falling back when it won't play).
function sourcesFor(key as dynamic) as object
    out = []
    if key = invalid then return out
    i = m.pl.keyIndex[key]
    if i = invalid then return out
    alts = m.pl.alts[m.pl.shown[i].ToStr()]
    if alts <> invalid then
        for each a in alts
            out.Push(m.pl.urls[a])
        end for
    end if
    return out
end function

' Hidden channels for Settings > Hidden channels: [key, name, group], by name.
function hiddenList() as object
    pl = m.pl
    rows = []
    for each k in hiddenSet()
        i = k.ToInt()
        rows.Push({ key: pl.keys[i], name: pl.names[i], group: pl.groupNames[pl.grp[i]], sort: LCase(pl.names[i]) })
    end for
    rows.SortBy("sort")
    out = []
    for each r in rows
        out.Push([r.key, r.name, r.group])
    end for
    return out
end function

' Games on right now, one per game, by league then title. Rebuilt when a show ends.
function sportsList() as object
    now = nowSecs()
    srv = serverSportsList() ' linked: the stream server's list (it knows which programmes are live)
    if srv <> invalid then return srv
    hidden = hiddenSet()
    if m.sportsCache <> invalid and now < m.sportsUntil then return m.sportsCache
    ensureNowIndex(now)
    initSportsRules()
    pl = m.pl
    games = []
    seen = {}
    seen.SetModeCaseSensitive()
    idx = m.nowIdx
    if idx.titles <> invalid then
        for e = 0 to idx.titles.Count() - 1
            members = pl.tvgIndex[idx.tvgs[e]]
            if members <> invalid and idx.ends[e] > now then
                s = pl.shown[members[0]]
                if not hidden.DoesExist(s.ToStr()) then
                    title = idx.titles[e]
                    chText = pl.names[s] + " " + pl.groupNames[pl.grp[s]]
                    if isGame(title, chText) then
                        gk = gameKey(title)
                        if not seen.DoesExist(gk) then
                            seen[gk] = true
                            games.Push(newGame(s, title, leagueOf(title, chText), idx.ends[e]))
                        end if
                    end if
                end if
            end if
        end for
    end if
    if pl.events <> invalid then
        for each s in pl.events
            if not hidden.DoesExist(s.ToStr()) and currentProgram(pl.tvg[s], now) = invalid then
                title = eventChannelTitle(pl.names[s], now)
                if title <> "" then
                    gk = gameKey(title)
                    if not seen.DoesExist(gk) then
                        seen[gk] = true
                        games.Push(newGame(s, title, leagueOf(title, pl.names[s] + " " + pl.groupNames[pl.grp[s]]), 0))
                    end if
                end if
            end if
        end for
    end if
    games.SortBy("sort")
    m.sportsCache = games
    m.sportsUntil = m.nowIdxUntil
    if m.sportsUntil > now + 60 then m.sportsUntil = now + 60
    return games
end function

function newGame(i as integer, title as string, league as string, endTime as integer) as object
    rank = 99
    leagues = m.sp.leagues
    for r = 0 to leagues.Count() - 1
        if leagues[r] = league then
            rank = r
            exit for
        end if
    end for
    return { i: i, title: title, league: league, endTime: endTime, sort: Right("0" + rank.ToStr(), 2) + LCase(title) }
end function
function currentProgram(tvgId as string, now as integer) as dynamic
    if tvgId = "" then return invalid
    lst = m.epg[tvgId]
    if lst = invalid then return invalid
    for each p in lst
        if p[0] <= now and p[1] > now then return p
        if p[0] > now then return invalid
    end for
    return invalid
end function

function nowFor(keys as dynamic) as object
    out = {}
    if keys = invalid then return out
    now = nowSecs()
    for each k in keys
        i = m.pl.keyIndex[k]
        if i <> invalid then
            p = currentProgram(m.pl.tvg[i], now)
            if p <> invalid then out[k] = [p[2], p[0], p[1]]
        end if
    end for
    return out
end function

function windowFor(keys as dynamic, startTime as integer, endTime as integer) as object
    out = {}
    if keys = invalid then return out
    for each k in keys
        progs = []
        i = m.pl.keyIndex[k]
        if i <> invalid and m.pl.tvg[i] <> "" then
            lst = m.epg[m.pl.tvg[i]]
            if lst <> invalid then
                for each p in lst
                    if p[1] > startTime and p[0] < endTime then progs.Push(p)
                end for
            end if
        end if
        out[k] = progs
    end for
    return out
end function

function nowNext(key as dynamic) as object
    out = { key: key, now: invalid, next: invalid }
    if key = invalid then return out
    i = m.pl.keyIndex[key]
    if i = invalid or m.pl.tvg[i] = "" then return out
    lst = m.epg[m.pl.tvg[i]]
    if lst = invalid then return out
    now = nowSecs()
    for each p in lst
        if p[0] <= now and p[1] > now then
            out.now = p
        else if p[0] > now then
            out.next = p
            exit for
        end if
    end for
    return out
end function

' ---------------------------------------------------------------- search

' Search scans with native Instr over one joined string instead of looping over
' thousands of entries in BrightScript: only matches cost script time.
'   blob = chr(10) + "name one" + chr(10) + "name two" + chr(10) ...
'   offs[i] = 1-based position where entry i starts in blob.
sub buildNameIndex(pl as object)
    lnames = []
    offs = []
    cursor = 2
    for each n in pl.names
        ln = searchKey(n)
        lnames.Push(ln)
        offs.Push(cursor)
        cursor = cursor + Len(ln) + 1
    end for
    pl.nameBlob = chr(10) + lnames.Join(chr(10)) + chr(10)
    pl.nameOffs = offs
end sub

' Index of the entry containing blob position p (largest i with offs[i] <= p).
function entryAt(offs as object, p as integer) as integer
    lo = 0
    hi = offs.Count() - 1
    while lo < hi
        mid = (lo + hi + 1) \ 2
        if offs[mid] <= p then
            lo = mid
        else
            hi = mid - 1
        end if
    end while
    return lo
end function

' What's airing right now, as a searchable blob of lower-cased titles plus parallel
' arrays. Rebuilt when a programme in it ends or the guide changes - normally from the
' idle loop, so a search almost never pays for it.
sub ensureNowIndex(now as integer)
    if now < m.nowIdxUntil then return
    titles = []
    ltitles = []
    ends = []
    tvgs = []
    offs = []
    cursor = 2
    untilT = now + 3600
    for each tvgId in m.epg
        p = currentProgram(tvgId, now)
        if p <> invalid then
            lt = searchKey(p[2])
            titles.Push(p[2])
            ltitles.Push(lt)
            ends.Push(p[1])
            tvgs.Push(tvgId)
            offs.Push(cursor)
            cursor = cursor + Len(lt) + 1
            if p[1] < untilT then untilT = p[1]
        end if
    end for
    m.nowIdx = { blob: chr(10) + ltitles.Join(chr(10)) + chr(10), offs: offs, titles: titles, ends: ends, tvgs: tvgs }
    m.nowIdxUntil = untilT
end sub

' Partial, case-insensitive search. Shows airing now whose title matches come first
' (e.g. "braves", "white sox"), then channels whose name matches ("espn",
' "sec network") with whatever they're showing now.
function search(query as dynamic) as object
    events = []
    channels = []
    if query = invalid then return events
    q = searchKey(query)
    if Len(q) < 2 or Instr(1, q, chr(10)) > 0 then return events
    now = nowSecs()
    pl = m.pl
    seen = {}
    hidden = hiddenSet()

    ensureNowIndex(now)
    idx = m.nowIdx
    if idx.offs <> invalid and idx.offs.Count() > 0 then
        p = Instr(1, idx.blob, q)
        while p > 0 and events.Count() < 100
            e = entryAt(idx.offs, p)
            members = pl.tvgIndex[idx.tvgs[e]]
            if members <> invalid then
                ' Copies of a channel show up once, as the channel shown for them; hidden ones not at all.
                for each i in members
                    s = pl.shown[i]
                    if not seen.DoesExist(pl.keys[s]) and not hidden.DoesExist(s.ToStr()) then
                        events.Push({ title: idx.titles[e], channel: pl.names[s], key: pl.keys[s], group: pl.groupNames[pl.grp[s]], endTime: idx.ends[e] })
                        seen[pl.keys[s]] = true
                    end if
                end for
            end if
            ' continue after this entry
            if e + 1 < idx.offs.Count() then
                p = Instr(idx.offs[e + 1], idx.blob, q)
            else
                p = 0
            end if
        end while
        events.SortBy("title")
    end if

    offs = pl.nameOffs
    if offs <> invalid and offs.Count() > 0 then
        p = Instr(1, pl.nameBlob, q)
        while p > 0 and channels.Count() < 100
            hit = entryAt(offs, p)
            i = pl.shown[hit]
            k = pl.keys[i]
            if not seen.DoesExist(k) and not hidden.DoesExist(i.ToStr()) then
                seen[k] = true
                cp = currentProgram(pl.tvg[i], now)
                if cp <> invalid then
                    channels.Push({ title: cp[2], channel: pl.names[i], key: k, group: pl.groupNames[pl.grp[i]], endTime: cp[1] })
                else
                    channels.Push({ title: pl.names[i], channel: pl.groupNames[pl.grp[i]], key: k, group: pl.groupNames[pl.grp[i]], endTime: 0 })
                end if
            end if
            if hit + 1 < offs.Count() then
                p = Instr(offs[hit + 1], pl.nameBlob, q)
            else
                p = 0
            end if
        end while
    end if
    events.Append(channels)
    return events
end function

' Search key: lower-case with spaces and common punctuation removed, so typing on a
' remote without spaces still matches ("whitesox" finds "White Sox", "secnetwork"
' finds "SEC Network").
function searchKey(s as string) as string
    k = LCase(s)
    if Instr(1, k, " ") > 0 then k = k.Replace(" ", "")
    if Instr(1, k, "-") > 0 then k = k.Replace("-", "")
    if Instr(1, k, ".") > 0 then k = k.Replace(".", "")
    if Instr(1, k, "'") > 0 then k = k.Replace("'", "")
    return k
end function

' Providers append Unicode superscript tags ("ᴸᶦᵛᵉ", "ᴺᵉʷ") that Roku fonts can't draw.
function cleanTitle(t as string) as string
    if Instr(1, t, "ᴸᶦᵛᵉ") > 0 then t = t.Replace("ᴸᶦᵛᵉ", "LIVE")
    if Instr(1, t, "ᴺᵉʷ") > 0 then t = t.Replace("ᴺᵉʷ", "NEW")
    return t.Trim()
end function

' ---------------------------------------------------------------- setup codes

' One-time code from tv.thecoxhome.com ("Set up a TV") -> playlist and guide links.
function redeemSetupCode(code as dynamic) as object
    if code = invalid or code = "" then return { error: "Enter the code from tv.thecoxhome.com." }
    ut = CreateObject("roUrlTransfer")
    port = CreateObject("roMessagePort")
    ut.SetMessagePort(port)
    ' kind / name also link this Roku with the stream server (remote control, playing
    ' through the server); it shows in the remote under its Roku device name.
    name = CreateObject("roDeviceInfo").GetFriendlyName()
    if name = invalid or name = "" then name = "Roku"
    ut.SetUrl(streamServer() + "/setup/" + code + "?kind=roku&name=" + ut.Escape(name))
    ut.SetCertificatesFile("common:/certs/ca-bundle.crt")
    ut.InitClientCertificates()
    ut.RetainBodyOnError(true)
    if not ut.AsyncGetToString() then return { error: "Couldn't reach the setup server." }
    msg = wait(15000, port)
    if msg = invalid then
        ut.AsyncCancel()
        return { error: "The setup server didn't answer. Check the Roku's internet connection." }
    end if
    status = msg.GetResponseCode()
    if status = 404 then return { error: "That code didn't work. Codes work once and expire after 10 minutes - get a new one on tv.thecoxhome.com." }
    if status = 429 then return { error: "Too many wrong codes. Wait a few minutes and try again." }
    if status <> 200 then return { error: "The setup server answered " + status.ToStr() + ". Try again in a moment." }
    data = ParseJson(msg.GetString())
    if data = invalid or data.m3u = invalid or data.m3u = "" then return { error: "The setup server didn't send a playlist." }
    guide = data.app_guide
    if guide = invalid or guide = "" then guide = data.epg
    if guide = invalid then guide = ""
    device = ""
    if data.device <> invalid then device = data.device
    deviceName = ""
    if data.device_name <> invalid then deviceName = data.device_name
    return { m3u: data.m3u, guide: guide, device: device, deviceName: deviceName, error: "" }
end function

' Settings sync while the app is open: every 5 minutes, and soon after favorites, hidden
' channels, categories or teams change here.
sub maybeSyncPrefs()
    now = nowSecs()
    if now - m.lastSyncCheck < 20 then return
    m.lastSyncCheck = now
    if regRead("deviceToken") = "" or m.pl.names.Count() = 0 then return
    snap = regRead("favorites") + "|" + regRead("hidden") + "|" + regRead("categories") + "|" + regRead("teams")
    if snap <> m.syncSnapshot or now - m.lastSync >= 300 then
        syncPrefs()
        m.lastSync = now
        m.syncSnapshot = regRead("favorites") + "|" + regRead("hidden") + "|" + regRead("categories") + "|" + regRead("teams")
    end if
end sub
