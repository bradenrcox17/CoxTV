' Data from the CoxOnAir stream server for a linked Roku (set up with a code): live sports,
' the College Football guide, synced settings and the ready-made channel list.
' Part of DataService (runs on its task thread).

' GET /d/<token><path> as parsed JSON, or invalid (not linked, offline, error).
function deviceGet(path as string) as dynamic
    token = regRead("deviceToken")
    if token = "" then return invalid
    ut = CreateObject("roUrlTransfer")
    port = CreateObject("roMessagePort")
    ut.SetMessagePort(port)
    ut.SetUrl(streamServer() + "/d/" + token + path)
    ut.SetCertificatesFile("common:/certs/ca-bundle.crt")
    ut.InitClientCertificates()
    ut.EnableEncodings(true)
    if not ut.AsyncGetToString() then return invalid
    msg = wait(10000, port)
    if msg = invalid then
        ut.AsyncCancel()
        return invalid
    end if
    if msg.GetResponseCode() <> 200 then return invalid
    return ParseJson(msg.GetString())
end function

' PUT /d/<token><path> with a JSON body; returns the parsed reply or invalid.
function devicePut(path as string, body as object) as dynamic
    token = regRead("deviceToken")
    if token = "" then return invalid
    ut = CreateObject("roUrlTransfer")
    port = CreateObject("roMessagePort")
    ut.SetMessagePort(port)
    ut.SetUrl(streamServer() + "/d/" + token + path)
    ut.SetCertificatesFile("common:/certs/ca-bundle.crt")
    ut.InitClientCertificates()
    ut.SetRequest("PUT")
    ut.AddHeader("Content-Type", "application/json")
    if not ut.AsyncPostFromString(FormatJson(body)) then return invalid
    msg = wait(10000, port)
    if msg = invalid then
        ut.AsyncCancel()
        return invalid
    end if
    if msg.GetResponseCode() <> 200 then return invalid
    return ParseJson(msg.GetString())
end function

' Stream server channel id -> playlist index (first copy).
function serverIndex() as object
    if m.serverIdx = invalid then
        pl = m.pl
        idx = {}
        for i = 0 to pl.urls.Count() - 1
            sid = serverIdOf(pl.urls[i])
            if sid <> "" and not idx.DoesExist(sid) then idx[sid] = i
        end for
        m.serverIdx = idx
    end if
    return m.serverIdx
end function

' ---------------------------------------------------------------- live sports

' Sports on now from the server (it reads the provider's full guide, including which
' programmes are live), or invalid when not linked / unreachable.
function serverSportsList() as dynamic
    now = nowSecs()
    if m.srvSports <> invalid and now < m.srvSportsUntil then return m.srvSports
    if regRead("deviceToken") = "" then return invalid
    if m.srvSportsFailedAt <> invalid and now - m.srvSportsFailedAt < 120 then return invalid
    data = deviceGet("/sports")
    if data = invalid or type(data.games) <> "roArray" then
        m.srvSportsFailedAt = now
        return invalid
    end if
    pl = m.pl
    idx = serverIndex()
    hidden = hiddenSet()
    seen = {}
    games = []
    n = 0
    for each g in data.games
        i = idx[g.id]
        if i <> invalid then
            s = pl.shown[i]
            sk = s.ToStr()
            if not hidden.DoesExist(sk) and not seen.DoesExist(sk) then
                seen[sk] = true
                endTime = 0
                e = g.Lookup("end")
                if e <> invalid then endTime = e
                games.Push({ i: s, title: g.title, league: g.league, endTime: endTime, sort: Right("0000" + n.ToStr(), 5) })
                n = n + 1
            end if
        end if
    end for
    m.srvSports = games
    m.srvSportsUntil = now + 60
    return games
end function

' ---------------------------------------------------------------- College Football guide

function cfbGames() as object
    now = nowSecs()
    if m.cfb <> invalid and now < m.cfbUntil then return m.cfb
    data = deviceGet("/cfb")
    if data = invalid or type(data.games) <> "roArray" then
        if m.cfb = invalid then m.cfb = []
        m.cfbUntil = now + 60
        return m.cfb
    end if
    pl = m.pl
    idx = serverIndex()
    mine = {}
    for each t in favoriteTeams().ncaaf
        mine[t.key] = true
    end for
    rows = []
    for each g in data.games
        key = ""
        if type(g.channels) = "roArray" then
            for each sid in g.channels
                i = idx[sid]
                if i <> invalid and key = "" then key = pl.keys[pl.shown[i]]
            end for
        end if
        isMine = false
        keys = []
        if type(g.team_keys) = "roArray" then keys = g.team_keys
        for each k in keys
            if mine.DoesExist(k) then isMine = true
        end for
        conf = "Other games"
        if type(g.conferences) = "roArray" and g.conferences.Count() > 0 and g.conferences[0] <> invalid then conf = g.conferences[0]
        live = g.game_state = "live"
        section = "1"
        if conf = "Other games" then section = "2"
        if isMine then section = "0"
        liveSort = "1"
        if live then liveSort = "0"
        kick = ""
        if g.kickoff_sort <> invalid then kick = g.kickoff_sort
        rows.Push({ game: g, key: key, mine: isMine, conf: conf, live: live, teamKeys: keys,
                    sort: section + LCase(conf) + "|" + liveSort + kick })
    end for
    rows.SortBy("sort")
    m.cfb = rows
    m.cfbUntil = now + 60
    return rows
end function

function cfbMatchup(g as object) as string
    text = ""
    if g.matchup <> invalid then text = g.matchup
    ranks = g.ranks
    if type(ranks) <> "roArray" then return text
    ranked = false
    for each r in ranks
        if r <> invalid and type(r) <> "roInvalid" then ranked = true
    end for
    if not ranked then return text
    re = CreateObject("roRegex", "\s+(?:vs\.?|at|@)\s+", "i")
    parts = re.Split(text)
    sepMatch = re.Match(text)
    if parts.Count() < 2 or sepMatch.Count() = 0 then return text
    t0 = ""
    t1 = ""
    if ranks.Count() > 0 and ranks[0] <> invalid and type(ranks[0]) <> "roInvalid" then t0 = "#" + ranks[0].ToStr() + " "
    if ranks.Count() > 1 and ranks[1] <> invalid and type(ranks[1]) <> "roInvalid" then t1 = "#" + ranks[1].ToStr() + " "
    return t0 + parts[0] + sepMatch[0] + t1 + parts[1]
end function

function cfbStatus(g as object) as string
    if g.game_state = "live" then
        out = []
        for each f in [g.score, g.period, g.clock]
            if f <> invalid and type(f) <> "roInvalid" and f <> "" then out.Push(f)
        end for
        if out.Count() = 0 then return "LIVE"
        return "LIVE  " + out.Join("  ")
    end if
    out = []
    for each f in [g.kickoff, g.channel_name]
        if f <> invalid and type(f) <> "roInvalid" and f <> "" then out.Push(f)
    end for
    return out.Join("  -  ")
end function

' Rows for the College Football category, in the channel-list row format.
function cfbList() as object
    pl = m.pl
    items = []
    for each r in cfbGames()
        g = r.game
        title = cfbMatchup(g)
        status = cfbStatus(g)
        if r.key = "" then status = status + "  (no channel found yet)"
        url = ""
        logo = ""
        num = ""
        i = pl.keyIndex[r.key]
        if i <> invalid then
            url = pl.urls[i]
            num = pl.nums[i]
        end if
        group = r.conf
        if r.mine then group = "Your teams"
        items.Push([r.key, title, url, logo, group, num, r.mine, status, 0, 0])
    end for
    return items
end function

' The two teams of a College Football game (for starring them), by row index.
function cfbTeams(index as dynamic) as object
    rows = cfbGames()
    if index = invalid or index < 0 or index >= rows.Count() then return []
    r = rows[index]
    names = CreateObject("roRegex", "\s+(?:vs\.?|at|@)\s+", "i").Split(r.game.matchup)
    out = []
    for i = 0 to r.teamKeys.Count() - 1
        display = r.teamKeys[i]
        if i < names.Count() and names[i].Trim() <> "" then display = names[i].Trim()
        out.Push({ key: r.teamKeys[i], display: display, mine: isFavoriteTeam(r.teamKeys[i]) })
    end for
    return out
end function

' ---------------------------------------------------------------- settings sync

' Values as the other apps and the web player store them (stream server channel ids).
function syncRead(name as string) as dynamic
    pl = m.pl
    if name = "favorites" or name = "hidden" then
        if name = "favorites" then keys = favKeys() else keys = hiddenKeys()
        out = []
        seen = {}
        for each k in keys
            i = pl.keyIndex[k]
            if i <> invalid then
                sid = serverIdOf(pl.urls[i])
                if sid <> "" and not seen.DoesExist(sid) then
                    seen[sid] = true
                    out.Push(sid)
                end if
            end if
        end for
        return out
    else if name = "categories" then
        return categoryOrder()
    else if name = "teams" then
        return favoriteTeams()
    end if
    return invalid
end function

sub syncWrite(name as string, value as dynamic)
    pl = m.pl
    if name = "favorites" or name = "hidden" then
        if type(value) <> "roArray" then return
        idx = serverIndex()
        keys = []
        seen = {}
        for each sid in value
            i = idx[sid]
            if i <> invalid then
                k = pl.keys[i]
                if not seen.DoesExist(k) then
                    seen[k] = true
                    keys.Push(k)
                end if
            end if
        end for
        if name = "favorites" then regWrite("favorites", keys.Join(",")) else regWrite("hidden", keys.Join(","))
        if name = "hidden" then
            resetListCaches()
            m.bus.channelCount = allShown().Count() ' after hiding
        end if
    else if name = "categories" then
        if type(value) = "roArray" and value.Count() > 0 then setCategoryOrder(value)
    else if name = "teams" then
        if type(value) = "roAssociativeArray" then regWrite("teams", FormatJson(value))
    end if
end sub

function syncMerge(name as string, server as dynamic, localValue as dynamic) as dynamic
    if name = "categories" then return server
    if name = "teams" then
        out = { ncaaf: [] }
        seen = {}
        for each src in [server, localValue]
            if type(src) = "roAssociativeArray" and type(src.ncaaf) = "roArray" then
                for each t in src.ncaaf
                    if t.key <> invalid and not seen.DoesExist(t.key) then
                        seen[t.key] = true
                        out.ncaaf.Push(t)
                    end if
                end for
            end if
        end for
        return out
    end if
    out = []
    seen = {}
    for each src in [server, localValue]
        if type(src) = "roArray" then
            for each v in src
                if not seen.DoesExist(v) then
                    seen[v] = true
                    out.Push(v)
                end if
            end for
        end if
    end for
    return out
end function

function syncHash(value as dynamic) as string
    ba = CreateObject("roByteArray")
    ba.FromAsciiString(FormatJson(value))
    d = CreateObject("roEVPDigest")
    d.Setup("md5")
    return d.Process(ba)
end function

function syncIsEmpty(name as string, value as dynamic) as boolean
    if name = "teams" then return type(value) <> "roAssociativeArray" or type(value.ncaaf) <> "roArray" or value.ncaaf.Count() = 0
    if name = "categories" then return regRead("categories") = ""
    return type(value) <> "roArray" or value.Count() = 0
end function

' Newest change wins, field by field; the first sync from this Roku merges favorites, hidden
' channels and teams from both sides. Returns true if anything here changed.
function syncPrefs() as boolean
    if regRead("deviceToken") = "" or m.pl.names.Count() = 0 then return false
    data = deviceGet("/prefs")
    if data = invalid or type(data.fields) <> "roAssociativeArray" then return false
    server = data.fields
    state = ParseJson(regRead("syncState", "{}"))
    if type(state) <> "roAssociativeArray" then state = {}
    push = {}
    changed = false
    nowMs = CreateObject("roDateTime").AsSeconds() * 1000&
    for each name in ["favorites", "categories", "hidden", "teams"]
        localValue = syncRead(name)
        localHash = syncHash(localValue)
        srv = server[name]
        known = state[name]
        srvT = -1&
        if type(srv) = "roAssociativeArray" and srv.t <> invalid then srvT = srv.t
        if type(known) <> "roAssociativeArray" then
            if type(srv) = "roAssociativeArray" and not syncIsEmpty(name, localValue) then
                merged = syncMerge(name, srv.v, localValue)
                syncWrite(name, merged)
                push[name] = { v: merged, t: nowMs }
                state[name] = { t: nowMs, h: syncHash(syncRead(name)) }
                changed = true
            else if type(srv) = "roAssociativeArray" then
                syncWrite(name, srv.v)
                state[name] = { t: srvT, h: syncHash(syncRead(name)) }
                changed = true
            else if not syncIsEmpty(name, localValue) then
                push[name] = { v: localValue, t: nowMs }
                state[name] = { t: nowMs, h: localHash }
            end if
        else if localHash <> known.h then
            value = localValue
            if (name = "favorites" or name = "hidden") and type(srv) = "roAssociativeArray" and type(srv.v) = "roArray" then
                ' Keep entries this Roku can't show (not in its playlist), so they aren't lost elsewhere.
                idx = serverIndex()
                value = []
                seen = {}
                for each v in localValue
                    value.Push(v)
                    seen[v] = true
                end for
                for each v in srv.v
                    if not idx.DoesExist(v) and not seen.DoesExist(v) then
                        value.Push(v)
                        seen[v] = true
                    end if
                end for
            end if
            push[name] = { v: value, t: nowMs }
            state[name] = { t: nowMs, h: localHash }
        else if type(srv) = "roAssociativeArray" and srvT > known.t then
            syncWrite(name, srv.v)
            state[name] = { t: srvT, h: syncHash(syncRead(name)) }
            changed = true
        end if
    end for
    if push.Count() > 0 then devicePut("/prefs", { fields: push })
    regWrite("syncState", FormatJson(state))
    if changed then
        m.cfbUntil = 0 ' teams may have changed
        m.bus.prefsVersion = m.bus.prefsVersion + 1
    end if
    return changed
end function

' ---------------------------------------------------------------- ready-made channel list

' The server's channel list, when it was built from this Roku's playlist: a few hundred KB
' instead of the provider's 20 MB playlist. Returns a playlist object, or invalid.
function serverPlaylist(url as string) as dynamic
    if regRead("deviceToken") = "" then return invalid
    path = "cachefs:/channels-server.json"
    m.bus.status = "Downloading channel list..."
    if not downloadToFile(streamServer() + "/d/" + regRead("deviceToken") + "/channels.json", path) then return invalid
    m.bus.status = "Loading channels..."
    data = ParseJson(ReadAsciiFile(path))
    CreateObject("roFileSystem").Delete(path)
    if type(data) <> "roAssociativeArray" or type(data.channels) <> "roArray" then return invalid
    ba = CreateObject("roByteArray")
    ba.FromAsciiString(url.Trim())
    d = CreateObject("roEVPDigest")
    d.Setup("sha256")
    if data.m3u <> Left(d.Process(ba), 16) then return invalid ' built from a different playlist
    pl = newPlaylist()
    if data.epg <> invalid then pl.detectedEpg = data.epg
    m.digest = CreateObject("roEVPDigest")
    m.keyBytes = CreateObject("roByteArray")
    q = chr(34)
    n = 0
    for each row in data.channels
        ' Rebuild the #EXTINF attributes addChannel reads: [name, url, tvg-id, logo, group, chno].
        info = "#EXTINF:-1"
        if row[2] <> invalid then info = info + " tvg-id=" + q + row[2] + q
        if row[3] <> invalid then info = info + " tvg-logo=" + q + row[3] + q
        if row[4] <> invalid then info = info + " group-title=" + q + row[4] + q
        if row[5] <> invalid then info = info + " tvg-chno=" + q + row[5] + q
        m.pendingGroup = ""
        addChannel(info + "," + row[0], row[1], pl)
        n = n + 1
        if n mod 2000 = 0 then
            m.bus.status = "Loading channels... " + n.ToStr()
            pumpRequests()
        end if
    end for
    m.digest = invalid
    m.keyBytes = invalid
    if pl.names.Count() = 0 then return invalid
    print "CoxTV: ready-made channel list, "; pl.names.Count(); " channels"
    buildNameIndex(pl)
    m.bus.status = "Combining duplicate channels..."
    buildGroups(pl)
    return pl
end function
