' Shared helpers, included by MainScene, every screen and the DataService task.

' Sends a request to the DataService task. The result arrives on a private reply node,
' so concurrent requests can never overwrite each other. Returns the reply node so the
' caller can ignore stale replies with isSameNode().
function svcCall(req as object, callback as string) as object
    reply = CreateObject("roSGNode", "Node")
    reply.addField("result", "assocarray", false)
    reply.observeField("result", callback)
    req.reply = reply
    m.global.bus.request = req
    return reply
end function

' Builds a channel ContentNode list (on the calling, i.e. render, thread) from the
' compact rows DataService returns. Field mapping used by every screen:
'   id = channel key, title = name, url = stream, hdPosterUrl = logo,
'   shortDescriptionLine1 = group, shortDescriptionLine2 = channel number,
'   starRating = 100 when favorite, description = now playing,
'   playStart / length = now-playing start / end (epoch seconds).
function listToContent(items as dynamic) as object
    root = CreateObject("roSGNode", "ContentNode")
    if items <> invalid then appendContentRows(root, items, 0, items.Count())
    return root
end function

' Appends rows [start, start+count) to root; returns the next unprocessed index.
' Lets big lists be built a batch at a time so the UI never stalls.
function appendContentRows(root as object, items as object, start as integer, count as integer) as integer
    last = start + count - 1
    if last >= items.Count() then last = items.Count() - 1
    for i = start to last
        r = items[i]
        star = 0
        if r[6] then star = 100
        root.CreateChild("ContentNode").setFields({
            id: r[0]
            title: r[1]
            url: r[2]
            hdPosterUrl: r[3]
            shortDescriptionLine1: r[4]
            shortDescriptionLine2: r[5]
            starRating: star
            description: r[7]
            playStart: r[8]
            length: r[9]
        })
    end for
    return last + 1
end function

function nowSecs() as integer
    return CreateObject("roDateTime").AsSeconds()
end function

function fmtClock(secs as integer) as string
    dt = CreateObject("roDateTime")
    dt.FromSeconds(secs)
    dt.ToLocalTime()
    h = dt.GetHours()
    mm = Right("0" + dt.GetMinutes().ToStr(), 2)
    if m.clock24 = invalid then m.clock24 = (CreateObject("roDeviceInfo").GetClockFormat() = "24h")
    if m.clock24 then return h.ToStr() + ":" + mm
    suffix = "AM"
    if h >= 12 then suffix = "PM"
    h12 = h mod 12
    if h12 = 0 then h12 = 12
    return h12.ToStr() + ":" + mm + " " + suffix
end function

function fmtRange(startSecs as integer, endSecs as integer) as string
    return fmtClock(startSecs) + " - " + fmtClock(endSecs)
end function

function fmtDay(secs as integer) as string
    dt = CreateObject("roDateTime")
    dt.FromSeconds(secs)
    dt.ToLocalTime()
    months = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"]
    return Left(dt.GetWeekday(), 3) + ", " + months[dt.GetMonth() - 1] + " " + dt.GetDayOfMonth().ToStr()
end function

function minsLeftText(endSecs as integer) as string
    mins = (endSecs - nowSecs()) \ 60
    if mins < 0 then mins = 0
    if mins >= 60 then return (mins \ 60).ToStr() + "h " + (mins mod 60).ToStr() + "m left"
    return mins.ToStr() + " min left"
end function

function initials(name as string) as string
    return UCase(Left(name.Trim(), 3))
end function

function categoryLabel(key as dynamic) as string
    if key = invalid then return ""
    if key = "__fav__" then return "Favorites"
    if key = "__all__" then return "All Channels"
    return key
end function

' ---- Registry (persists across launches) ----

function regRead(key as string, default = "" as string) as string
    sec = CreateObject("roRegistrySection", "CoxTV")
    if sec.Exists(key) then return sec.Read(key)
    return default
end function

sub regWrite(key as string, value as string)
    sec = CreateObject("roRegistrySection", "CoxTV")
    sec.Write(key, value)
    sec.Flush()
end sub

function favKeys() as object
    out = []
    raw = regRead("favorites")
    if raw = "" then return out
    for each k in raw.Split(",")
        if k <> "" then out.Push(k)
    end for
    return out
end function

sub setFavKeys(keys as object)
    regWrite("favorites", keys.Join(","))
end sub

' Categories shown in the sidebar, in the user's order ("__fav__", "__all__" or group names).
' Out of the box only Favorites and All Channels; groups are added in Settings.
function categoryOrder() as object
    raw = regRead("categories")
    if raw <> "" then
        v = ParseJson(raw)
        if type(v) = "roArray" then return v
    end if
    return ["__fav__", "__all__"]
end function

sub setCategoryOrder(keys as object)
    regWrite("categories", FormatJson(keys))
end sub

' Adds or removes a channel key from favorites. Returns true if it is now a favorite.
function toggleFavorite(key as string) as boolean
    out = []
    found = false
    for each k in favKeys()
        if k = key then
            found = true
        else
            out.Push(k)
        end if
    end for
    if not found then out.Push(key)
    regWrite("favorites", out.Join(","))
    return not found
end function

' ---- Playback ----

' Ordered list of {url, format} attempts for a stream. HLS is preferred: an
' "output=ts" or ".ts" URL is first tried as its .m3u8 equivalent, then as-is.
function streamCandidates(url as string) as object
    lower = LCase(url)
    q = Instr(1, lower, "?")
    if q > 0 then
        pathPart = Left(lower, q - 1)
    else
        pathPart = lower
    end if
    out = []
    o = Instr(1, lower, "output=ts")
    if o > 0 then
        out.Push({ url: Left(url, o - 1) + "output=m3u8" + Mid(url, o + 9), format: "hls" })
        out.Push({ url: url, format: "ts" })
    else if Right(pathPart, 3) = ".ts" then
        n = Len(pathPart)
        out.Push({ url: Left(url, n - 3) + ".m3u8" + Mid(url, n + 1), format: "hls" })
        out.Push({ url: url, format: "ts" })
    else if Right(pathPart, 5) = ".m3u8" or Right(pathPart, 4) = ".m3u" or Instr(1, lower, "output=m3u8") > 0 or Instr(1, lower, "output=hls") > 0 then
        out.Push({ url: url, format: "hls" })
    else if Right(pathPart, 4) = ".mp4" or Right(pathPart, 4) = ".m4v" or Right(pathPart, 4) = ".mov" then
        out.Push({ url: url, format: "mp4" })
    else if Right(pathPart, 4) = ".mkv" then
        out.Push({ url: url, format: "mkv" })
    else if Right(pathPart, 4) = ".mpd" then
        out.Push({ url: url, format: "dash" })
    else if isXtreamLive(pathPart) then
        ' Xtream short form http://host/user/pass/<id> (MPEG-TS): the same path with
        ' .m3u8 appended serves HLS.
        q2 = Len(pathPart)
        out.Push({ url: Left(url, q2) + ".m3u8" + Mid(url, q2 + 1), format: "hls" })
        out.Push({ url: url, format: "ts" })
    else
        ' Unknown (e.g. Threadfin /stream/<id>): usually MPEG-TS, sometimes HLS.
        out.Push({ url: url, format: "ts" })
        out.Push({ url: url, format: "hls" })
    end if
    return out
end function

' True for extension-less Xtream live URLs: .../<user>/<pass>/<numeric id>
' or .../live/<user>/<pass>/<numeric id>.
function isXtreamLive(pathPart as string) as boolean
    parts = []
    for each p in pathPart.Split("/")
        if p <> "" then parts.Push(p)
    end for
    ' parts: [scheme:, host, user, pass, id] at minimum
    if parts.Count() < 5 then return false
    last = parts[parts.Count() - 1]
    if Instr(1, last, ".") > 0 or last = "" then return false
    for i = 1 to Len(last)
        c = Asc(Mid(last, i, 1))
        if c < 48 or c > 57 then return false
    end for
    return true
end function
