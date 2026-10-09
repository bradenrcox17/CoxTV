' Duplicate channels and "Sports on now". Same rules as the Fire TV / phone apps
' (core/.../ChannelGroups.kt and Sports.kt) and the web player (coxstream.py).

' ---------------------------------------------------------------- US channels

' A US channel ("Sports on now" lists only these; search puts them first). The provider files
' them under "USA | ..." groups, ESPN+ and Flo Sports included though their names don't say
' USA. A group starting with another country's code ("CA | ...") is never US, whatever the name
' says; otherwise names starting "USA:" / "US:" / "(US)" are, and so are ESPN+, ESPN Play and
' Flo Sports unless the name starts with another country's code ("Arg: ESPN"). Same rule as the
' stream server (is_us_channel) and the Android apps (ChannelGroups.isUs).
function isUsChannel(name as string, group as string) as boolean
    if m.usRules = invalid then
        m.usRules = {
            group: CreateObject("roRegex", "^\s*USA?\b", "i")
            name: CreateObject("roRegex", "^\s*(USA?\s*[:|\-]|\(USA?\))", "i")
            service: CreateObject("roRegex", "espn\s*\+|espn\s*play|flo\s*sports", "i")
            country: CreateObject("roRegex", "^\s*\(?([A-Za-z]{2,4})\)?\s*[:|]", "")
        }
    end if
    r = m.usRules
    if r.group.IsMatch(group) then return true
    if not usOrNoCountry(r.country.Match(group)) then return false
    if r.name.IsMatch(name) then return true
    if r.service.IsMatch(name + " " + group) then return usOrNoCountry(r.country.Match(name))
    return false
end function

' ---------------------------------------------------------------- NFL / NBA / MLB / NHL
' Every local station carrying one of these leagues' games would list it again, so their games
' come from dedicated channels ("NFL: Tennessee Titans" in "USA | NFL Teams", "NFL 1: ..." in
' "USA | NFL Game Pass") or national ones, never local stations ("USA | LOCAL - CBS"...).
' Same rule as the stream server and the Android apps (Sports.kt).

function isProLeague(league as string) as boolean
    return league = "NFL" or league = "NBA" or league = "MLB" or league = "NHL"
end function

function isLocalGroup(group as string) as boolean
    initProRules()
    return m.proRules.local.IsMatch(group)
end function

' A league's own channel: team channels and Game Pass feeds.
function isDedicatedChannel(name as string, group as string) as boolean
    initProRules()
    return m.proRules.dedicatedGroup.IsMatch(group) or m.proRules.dedicatedName.IsMatch(name)
end function

' "PIT vs. CBJ • ANA vs. WPG" whip-around feeds (the bullet sometimes arrives garbled as "â€¢").
function isMultiGame(title as string) as boolean
    return Instr(1, title, chr(8226)) > 0 or Instr(1, title, chr(226) + chr(8364) + chr(162)) > 0
end function

sub initProRules()
    if m.proRules <> invalid then return
    m.proRules = {
        local: CreateObject("roRegex", "\blocal\b", "i")
        dedicatedGroup: CreateObject("roRegex", "^\s*USA?\s*\|\s*(NFL|NBA|MLB|NHL)\s+(Teams|Game\s*Pass)\s*$", "i")
        dedicatedName: CreateObject("roRegex", "^\s*(NFL|NBA|MLB|NHL)\s*\d*\s*:", "i")
    }
end sub

' ---------------------------------------------------------------- search order
' Same as the stream server (search_tier / name_match_rank) and the Android apps
' (ChannelGroups.searchTier / nameMatchRank).

' 0 US, 1 US Spanish-language (ESPN Deportes...), 2 other countries, 3 their Spanish ones.
function searchTier(name as string, group as string) as integer
    initSearchRules()
    tier = 2
    if isUsChannel(name, group) then tier = 0
    if m.searchRules.spanish.IsMatch(name + " " + group) then tier = tier + 1
    return tier
end function

' [rank, length] for a channel name vs a search, lower first: rank 0 the name (without its
' "USA:" / "(US)" / "Tubi:" prefix and quality tags) is the search, 1 it starts with it, 2 a
' word starts with it ("ESPN SEC Network"), 3 anywhere; event listings ("SEC Network +: LSU
' vs. Kentucky") +4. Length: shorter names first.
function nameMatchRank(name as string, query as string) as object
    initSearchRules()
    r = m.searchRules
    key = r.notAlnum.ReplaceAll(LCase(query), "")
    base = r.tags.ReplaceAll(r.prefix.Replace(name, ""), " ")
    lower = LCase(base)
    k = r.notAlnum.ReplaceAll(lower, "")
    rank = 3
    if k = key then
        rank = 0
    else if Left(k, Len(key)) = key then
        rank = 1
    else
        words = []
        for each w in r.splitter.Split(lower)
            if w <> "" then words.Push(w)
        end for
        rest = ""
        for i = words.Count() - 1 to 1 step -1
            rest = words[i] + rest
            if Left(rest, Len(key)) = key then
                rank = 2
                exit for
            end if
        end for
    end if
    if r.eventName.IsMatch(base) then rank = rank + 4
    return [rank, Len(k)]
end function

sub initSearchRules()
    if m.searchRules <> invalid then return
    m.searchRules = {
        spanish: CreateObject("roRegex", "deportes|espa\x{00f1}ol|espanol|spanish|\blatin|univision|telemundo|tudn|unimas|unim\x{00e1}s|galavision|galavisi\x{00f3}n", "i")
        prefix: CreateObject("roRegex", "^\s*(?:\([^)]*\)\s*|[a-z0-9+]{1,6}\s*[:|]\s*)+", "i")
        tags: CreateObject("roRegex", "\[[^\]]*\]|\b(?:hd|fhd|uhd|4k|sd|hevc|backup|bkp|raw|vip|\d{3,4}p)\b", "i")
        eventName: CreateObject("roRegex", ":|\s(?:vs\.?|v|at|@)\s", "i")
        notAlnum: CreateObject("roRegex", "[^a-z0-9]", "")
        splitter: CreateObject("roRegex", "[^a-z0-9]+", "")
    }
end sub

' A country-prefix match (from roRegex.Match) that is US, or no prefix at all.
function usOrNoCountry(match as object) as boolean
    if match.Count() < 2 then return true
    code = UCase(match[1])
    return code = "US" or code = "USA"
end function

' ---------------------------------------------------------------- duplicate channels

sub initGroupRules()
    if m.grp <> invalid then return
    tags = {}
    for each t in ["hd", "fhd", "uhd", "4k", "sd", "hevc", "h265", "h264", "50fps", "60fps", "1080p", "1080", "720p", "720", "2160p", "backup", "bkp", "alt", "b", "raw", "vip"]
        tags[t] = true
    end for
    m.grp = {
        tags: tags
        prefix: CreateObject("roRegex", "^([a-z]{2,4})\s*[|:]\s*", "")
        punct: [" ", "-", "_", ".", ",", ":", ";", "!", "?", "/", "|", "'", "`", chr(34), "~", "#", "@", "*"]
    }
end sub

' Grouping key: country prefix + name without quality/backup tags and punctuation.
function groupKey(name as string) as string
    g = m.grp
    n = LCase(name).Trim()
    prefix = ""
    mt = g.prefix.Match(n)
    if mt.Count() > 1 then
        prefix = mt[1]
        n = Mid(n, Len(mt[0]) + 1)
    end if
    n = dropTagBrackets(n)
    if Instr(1, n, ":)") > 0 then n = n.Replace(":)", " ")
    for each c in g.punct
        if Instr(1, n, c) > 0 then n = n.Replace(c, " ")
    end for
    words = []
    for each w in n.Split(" ")
        if w <> "" then words.Push(w)
    end for
    while words.Count() > 1 and g.tags.DoesExist(words.Peek().Replace(".", ""))
        words.Pop()
    end while
    if words.Count() = 0 then return "#" + name
    return prefix + "|" + words.Join(" ")
end function

' Removes "(HD)", "[FHD]", "(Backup)": brackets holding nothing but tags.
function dropTagBrackets(n as string) as string
    p = 1
    while true
        a = Instr(p, n, "(")
        b = Instr(p, n, "[")
        if a = 0 or (b > 0 and b < a) then a = b
        if a = 0 then exit while
        e = Instr(a, n, ")")
        e2 = Instr(a, n, "]")
        if e = 0 or (e2 > 0 and e2 < e) then e = e2
        if e = 0 then exit while
        inner = Mid(n, a + 1, e - a - 1)
        allTags = false
        for each w in inner.Replace("/", " ").Replace(",", " ").Split(" ")
            if w <> "" then
                if m.grp.tags.DoesExist(w.Replace(".", "")) then
                    allTags = true
                else
                    allTags = false
                    exit for
                end if
            end if
        end for
        if allTags then
            n = Left(n, a - 1) + " " + Mid(n, e + 1)
            p = a + 1
        else
            p = e + 1
        end if
    end while
    return n
end function

' Lower plays first: FHD, HD, untagged, UHD/4K, SD.
function qualityRank(name as string) as integer
    n = " " + LCase(name) + " "
    for each c in ["(", ")", "[", "]", "|", ":", "-", ".", ","]
        if Instr(1, n, c) > 0 then n = n.Replace(c, " ")
    end for
    if Instr(1, n, " fhd ") > 0 or Instr(1, n, " 1080 ") > 0 or Instr(1, n, " 1080p ") > 0 then return 0
    if Instr(1, n, " hd ") > 0 or Instr(1, n, " 720p ") > 0 then return 1
    if Instr(1, n, " uhd ") > 0 or Instr(1, n, " 4k ") > 0 or Instr(1, n, " 2160p ") > 0 then return 3
    if Instr(1, n, " sd ") > 0 then return 4
    return 2
end function

' Adds to a parsed playlist:
'   pl.shown[i]  index of the channel shown for channel i (itself when it's shown)
'   pl.alts      { "shown index": [other copies, best first] } for channels with copies
'   pl.events    shown channels named like a game ("X vs Y"), for Sports on now
' Copies borrow a guide id when the shown channel has none.
sub buildGroups(pl as object)
    initGroupRules()
    initSportsRules()
    n = pl.names.Count()
    byKey = {}
    byKey.SetModeCaseSensitive()
    groups = []
    for i = 0 to n - 1
        k = groupKey(pl.names[i])
        subs = byKey[k]
        if subs = invalid then
            subs = []
            byKey[k] = subs
        end if
        tvg = pl.tvg[i]
        found = invalid
        for each s in subs
            if tvg = "" or s.tvg = "" or s.tvg = tvg then
                found = s
                exit for
            end if
        end for
        if found = invalid then
            found = { tvg: tvg, items: [] }
            subs.Push(found)
            groups.Push(found)
        else if found.tvg = "" and tvg <> "" then
            found.tvg = tvg
        end if
        found.items.Push(i)
        if i mod 2000 = 1999 then pumpRequests()
    end for

    shown = CreateObject("roArray", n, false)
    alts = {}
    events = []
    for each g in groups
        items = g.items
        best = items[0]
        if items.Count() > 1 then
            ' best quality first, then playlist order (insertion sort; groups are tiny)
            ranked = []
            for each i in items
                r = qualityRank(pl.names[i])
                j = ranked.Count()
                ranked.Push({ i: i, r: r })
                while j > 0 and ranked[j - 1].r > r
                    ranked[j] = ranked[j - 1]
                    j = j - 1
                end while
                ranked[j] = { i: i, r: r }
            end for
            best = ranked[0].i
            others = []
            for k = 1 to ranked.Count() - 1
                others.Push(ranked[k].i)
            end for
            alts[best.ToStr()] = others
            if pl.tvg[best] = "" and g.tvg <> "" then pl.tvg[best] = g.tvg
        end if
        for each i in items
            shown[i] = best
        end for
        if m.sp.event.IsMatch(" " + LCase(pl.names[best]) + " ") then events.Push(best)
    end for
    pl.shown = shown
    pl.alts = alts
    pl.events = events
end sub

' ---------------------------------------------------------------- Sports on now

sub initSportsRules()
    if m.sp <> invalid then return
    w = function(list as string) as object
        return CreateObject("roRegex", "\b(" + list + ")\b", "")
    end function
    m.sp = {
        event: CreateObject("roRegex", "\s(vs\.?|v\.?|versus|@)\s", "")
        at: CreateObject("roRegex", "\sat\s", "")
        studio: ["highlight", "recap", "preview", "classic", "replay", "rewind", "postgame", "post-game", "post game", "pregame", "pre-game", "pre game", "countdown", "sportscenter", "fantasy", "the insiders", "best of", "top 10", "top ten", "magazine", "encore", "re-air", "no event", "no game", "off air", "offair", "standby", "stand by", "in 60", "condensed", "a confirmar", "to be announced", "inside", "weekly", "tonight", "news", " show ", "primetime"]
        sportsChannel: ["sport", "espn", "fox sports", "fs1", "fs2", "tnt", "tsn", "nfl", "nba", "mlb", "nhl", "ncaa", "sec network", "acc network", "big ten", "pac-12", "bein", "dazn", "eurosport", "golf", "tennis", "ufc", "wwe", "nascar", "bally", "fanduel", "msg", "nesn", "yes network", "marquee", "root sports", "ppv", "event", "f1"]
        gameWords: w("football|basketball|baseball|hockey|soccer|grand prix|race|ufc|boxing|wrestling|golf|tennis|cricket|rugby|fight")
        sportWords: w("football|basketball|baseball|hockey|soccer|grand prix|race|ufc|boxing|wrestling|golf|tennis|cricket|rugby|fight|futbol|fútbol|calcio|partido|nfl|nba|wnba|mlb|nhl|ncaa|mls")
        nfl: w("nfl|falcons|ravens|bills|bears|bengals|browns|cowboys|broncos|lions|packers|texans|colts|jaguars|chiefs|raiders|chargers|rams|dolphins|vikings|patriots|saints|eagles|steelers|49ers|seahawks|buccaneers|titans|commanders")
        nba: w("nba|hawks|celtics|nets|hornets|bulls|cavaliers|mavericks|nuggets|pistons|warriors|rockets|pacers|clippers|lakers|grizzlies|heat|bucks|timberwolves|pelicans|knicks|thunder|magic|76ers|sixers|suns|blazers|spurs|raptors|jazz|wizards")
        mlb: w("mlb|diamondbacks|braves|orioles|cubs|reds|guardians|rockies|tigers|astros|royals|angels|dodgers|marlins|brewers|twins|mets|yankees|athletics|phillies|pirates|padres|mariners|rays|nationals|red sox|white sox|blue jays")
        nhl: w("nhl|ducks|bruins|sabres|flames|hurricanes|blackhawks|avalanche|oilers|canadiens|predators|devils|islanders|senators|flyers|penguins|sharks|kraken|blues|lightning|canucks|capitals|mammoth|blue jackets|red wings|maple leafs|golden knights")
        college: w("college|ncaa|sec network|acc network|big ten|big 12|pac-12")
        soccer: w("soccer|futbol|fútbol|premier league|epl|la liga|laliga|serie a|bundesliga|ligue 1|champions league|europa|mls|liga mx|copa|fa cup|world cup|eredivisie|uefa|concacaf|fifa")
        fighting: w("ufc|boxing|mma|bellator|pfl|wwe|aew|wrestling|fight night")
        racing: w("formula 1|f1|nascar|indycar|motogp|grand prix|racing|supercross")
        leagues: ["NFL", "College Football", "NBA", "WNBA", "College Basketball", "MLB", "NHL", "Soccer", "Fighting", "Racing", "Golf", "Tennis", "Cricket", "Rugby", "College Sports", "Football", "Basketball", "Baseball", "Hockey", "Other"]
        word: {}
    }
end sub

function spWord(word as string) as object
    r = m.sp.word[word]
    if r = invalid then
        r = CreateObject("roRegex", "\b" + word + "\b", "")
        m.sp.word[word] = r
    end if
    return r
end function

function isStudio(lowerPadded as string) as boolean
    for each s in m.sp.studio
        if Instr(1, lowerPadded, s) > 0 then return true
    end for
    return false
end function

' True if a programme title (on this channel) looks like a game rather than a studio show.
function isGame(title as string, channelText as string) as boolean
    t = " " + LCase(title) + " "
    if isStudio(t) then return false
    c = LCase(channelText)
    onSports = false
    for each s in m.sp.sportsChannel
        if Instr(1, c, s) > 0 then
            onSports = true
            exit for
        end if
    end for
    ' "X vs Y" also appears in "Man vs. Wild" or Czech titles ("v" = "in"): off sports channels
    ' it counts only when the title names a sport, a league or a team.
    if m.sp.event.IsMatch(t) then
        if onSports or m.sp.sportWords.IsMatch(t) then return true
        sp = m.sp
        return sp.nfl.IsMatch(t) or sp.nba.IsMatch(t) or sp.mlb.IsMatch(t) or sp.nhl.IsMatch(t) or sp.soccer.IsMatch(t)
    end if
    if not onSports then return false
    return m.sp.at.IsMatch(t) or m.sp.gameWords.IsMatch(t)
end function

function leagueOf(title as string, channelText as string) as string
    sp = m.sp
    t = LCase(title + " " + channelText)
    lt = LCase(title)
    college = sp.college.IsMatch(t)
    ' League names first, then the sport named in the title, then team nicknames
    ' (so "flohockey: Mississauga Chargers vs ..." isn't taken for the NFL's Chargers).
    if spWord("nfl").IsMatch(t) then return "NFL"
    if spWord("wnba").IsMatch(t) then return "WNBA"
    if spWord("nba").IsMatch(t) then return "NBA"
    if spWord("mlb").IsMatch(t) then return "MLB"
    if spWord("nhl").IsMatch(t) then return "NHL"
    if college and Instr(1, t, "football") > 0 then return "College Football"
    if college and Instr(1, t, "basketball") > 0 then return "College Basketball"
    if sp.soccer.IsMatch(t) then return "Soccer"
    if sp.fighting.IsMatch(t) then return "Fighting"
    if sp.racing.IsMatch(t) then return "Racing"
    if spWord("golf").IsMatch(t) or spWord("pga").IsMatch(t) or spWord("lpga").IsMatch(t) then return "Golf"
    if spWord("tennis").IsMatch(t) or spWord("atp").IsMatch(t) or spWord("wta").IsMatch(t) then return "Tennis"
    if spWord("cricket").IsMatch(t) then return "Cricket"
    if spWord("rugby").IsMatch(t) then return "Rugby"
    if Instr(1, t, "hockey") > 0 then
        if sp.nhl.IsMatch(lt) then return "NHL"
        return "Hockey"
    end if
    if Instr(1, t, "baseball") > 0 then
        if sp.mlb.IsMatch(lt) then return "MLB"
        return "Baseball"
    end if
    if Instr(1, t, "basketball") > 0 then
        if sp.nba.IsMatch(lt) then return "NBA"
        return "Basketball"
    end if
    if not college and sp.nfl.IsMatch(lt) then return "NFL"
    if not college and sp.nba.IsMatch(lt) then return "NBA"
    if sp.mlb.IsMatch(lt) then return "MLB"
    if sp.nhl.IsMatch(lt) then return "NHL"
    if Instr(1, t, "football") > 0 then return "Football"
    if college then return "College Sports"
    return "Other"
end function

' Event channels named after their game, e.g. "(58) Senators vs. Bruins Oct 05 7:30PM ET
' (2026-10-05 19:30:00)": the game title if it's on around now (30 min before to 4 h after
' the start), else "". Names without a date are left out: providers reuse event channels
' and leave old names behind.
function eventChannelTitle(name as string, now as integer) as string
    lower = " " + LCase(name) + " "
    if not m.sp.event.IsMatch(lower) or isStudio(lower) then return ""
    if m.sp.eventDate = invalid then
        m.sp.eventDate = CreateObject("roRegex", "\s*\((\d{4})-(\d{2})-(\d{2}) (\d{2}):(\d{2})(?::\d{2})?(?: ?[A-Za-z]{2,4})?\)", "")
        m.sp.eventNum = CreateObject("roRegex", "^\(\d+\)\s*", "")
    end if
    starts = []
    mt = m.sp.eventDate.Match(name)
    if mt.Count() > 5 then
        y = mt[1].ToInt()
        mo = mt[2].ToInt()
        d = mt[3].ToInt()
        starts.Push(daysFromCivil(y, mo, d) * 86400 + mt[4].ToInt() * 3600 + mt[5].ToInt() * 60 + easternOffset(y, mo, d))
    else
        return "" ' a kickoff time without a date isn't enough: providers keep yesterday's names
    end if
    onNow = false
    for each s in starts
        if now >= s - 1800 and now <= s + 4 * 3600 then onNow = true
    end for
    if not onNow then return ""
    return m.sp.eventNum.Replace(m.sp.eventDate.Replace(name, ""), "").Trim()
end function

' Seconds to add to US Eastern wall-clock time to get UTC (EDT from the second Sunday of
' March to the first Sunday of November).
function easternOffset(y as integer, mo as integer, d as integer) as integer
    if mo < 3 or mo > 11 then return 5 * 3600
    if mo > 3 and mo < 11 then return 4 * 3600
    first = (daysFromCivil(y, mo, 1) + 4) mod 7   ' weekday of the 1st, 0 = Sunday
    firstSunday = 1 + (7 - first) mod 7
    if mo = 3 then
        if d >= firstSunday + 7 then return 4 * 3600
        return 5 * 3600
    end if
    if d < firstSunday then return 4 * 3600
    return 5 * 3600
end function

' Folds the same game shown on several channels into one entry ("NFL Football" = "NFL Football NEW").
function gameKey(title as string) as string
    if m.sp.keyNoise = invalid then m.sp.keyNoise = CreateObject("roRegex", "\([^)]*\)|\b(new|live|hd|fhd)\b", "")
    k = m.sp.keyNoise.ReplaceAll(LCase(title), " ")
    for each c in [" ", ".", "-", ":", "'", ",", "@", "(", ")", "!", "/", "|"]
        if Instr(1, k, c) > 0 then k = k.Replace(c, "")
    end for
    return k
end function
