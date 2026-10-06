' String helpers for M3U and XMLTV parsing. Pure functions; used by DataService.

' Value of key="..." in an #EXTINF line or XML start tag, or "".
function attrOf(text as string, key as string) as string
    needle = key + "=" + chr(34)
    p = Instr(1, text, needle)
    if p = 0 then return ""
    s = p + Len(needle)
    e = Instr(s, text, chr(34))
    if e = 0 then return ""
    return Mid(text, s, e - s)
end function

' Channel name in an #EXTINF line: text after the first comma that is not inside quotes.
function extinfName(line as string) as string
    c = Instr(1, line, ",")
    while c > 0
        if quotesBefore(line, c) mod 2 = 0 then return Mid(line, c + 1).Trim()
        c = Instr(c + 1, line, ",")
    end while
    return ""
end function

function quotesBefore(text as string, limit as integer) as integer
    n = 0
    p = Instr(1, text, chr(34))
    while p > 0 and p < limit
        n = n + 1
        p = Instr(p + 1, text, chr(34))
    end while
    return n
end function

' Text content of the first <tag>...</tag> in an XML fragment, or "".
function tagText(xml as string, tag as string) as string
    a = Instr(1, xml, "<" + tag)
    if a = 0 then return ""
    s = Instr(a, xml, ">")
    if s = 0 then return ""
    if Mid(xml, s - 1, 1) = "/" then return ""
    e = Instr(s, xml, "</" + tag + ">")
    if e = 0 then return ""
    t = Mid(xml, s + 1, e - s - 1).Trim()
    if Left(t, 9) = "<![CDATA[" then t = Mid(t, 10, Len(t) - 12)
    return t
end function

function xmlDecode(s as string) as string
    if Instr(1, s, "&") = 0 then return s
    s = s.Replace("&#xA;", " ").Replace("&#xa;", " ").Replace("&#10;", " ").Replace("&#xD;", "").Replace("&#13;", "")
    return s.Replace("&lt;", "<").Replace("&gt;", ">").Replace("&quot;", chr(34)).Replace("&apos;", "'").Replace("&#39;", "'").Replace("&amp;", "&")
end function

' XMLTV time ("20261005193000 +0100") to epoch seconds, or 0 if malformed.
function xmltvTime(s as string) as integer
    if Len(s) < 12 then return 0
    y = Mid(s, 1, 4).ToInt()
    mo = Mid(s, 5, 2).ToInt()
    d = Mid(s, 7, 2).ToInt()
    h = Mid(s, 9, 2).ToInt()
    mi = Mid(s, 11, 2).ToInt()
    sec = 0
    if Len(s) >= 14 then sec = Mid(s, 13, 2).ToInt()
    offset = 0
    rest = Mid(s, 15).Trim()
    if Len(rest) >= 5 then
        sign = Left(rest, 1)
        if sign = "+" or sign = "-" then
            offset = (Mid(rest, 2, 2).ToInt() * 60 + Mid(rest, 4, 2).ToInt()) * 60
            if sign = "-" then offset = -offset
        end if
    end if
    return daysFromCivil(y, mo, d) * 86400 + h * 3600 + mi * 60 + sec - offset
end function

' Epoch seconds -> "YYYYMMDDhhmmss" (UTC; add an offset first for local stamps).
function epochToStamp(secs as integer) as string
    days = secs \ 86400
    secOfDay = secs - days * 86400
    z = days + 719468
    era = z \ 146097
    doe = z - era * 146097
    yoe = (doe - doe \ 1460 + doe \ 36524 - doe \ 146096) \ 365
    y = yoe + era * 400
    doy = doe - (365 * yoe + yoe \ 4 - yoe \ 100)
    mp = (5 * doy + 2) \ 153
    d = doy - (153 * mp + 2) \ 5 + 1
    if mp < 10 then
        mo = mp + 3
    else
        mo = mp - 9
    end if
    if mo <= 2 then y = y + 1
    return y.ToStr() + pad2(mo) + pad2(d) + pad2(secOfDay \ 3600) + pad2((secOfDay mod 3600) \ 60) + pad2(secOfDay mod 60)
end function

function pad2(n as integer) as string
    if n < 10 then return "0" + n.ToStr()
    return n.ToStr()
end function

' Days since 1970-01-01 (Howard Hinnant's algorithm); integer math only.
function daysFromCivil(y as integer, mo as integer, d as integer) as integer
    if mo <= 2 then y = y - 1
    era = y \ 400
    yoe = y - era * 400
    if mo > 2 then
        mp = mo - 3
    else
        mp = mo + 9
    end if
    doy = (153 * mp + 2) \ 5 + d - 1
    doe = yoe * 365 + yoe \ 4 - yoe \ 100 + doy
    return era * 146097 + doe - 719468
end function

' Reads a file in ~256 KB pieces, never splitting a multi-byte UTF-8 character across
' pieces (lines CAN span pieces; callers carry the partial tail). Call reader.next()
' until it returns invalid.
function newChunkReader(path as string) as object
    size = 0
    st = CreateObject("roFileSystem").Stat(path)
    if st <> invalid and st.size <> invalid then size = st.size
    return { path: path, pos: 0, size: size, ba: CreateObject("roByteArray"), chunk: 262144, next: chunkReaderNext }
end function

function chunkReaderNext() as dynamic
    if m.pos >= m.size then return invalid
    length = m.chunk
    if m.pos + length > m.size then length = m.size - m.pos
    m.ba.ReadFile(m.path, m.pos, length)
    if m.pos + length < m.size then
        ' Inspect at most the last 3 bytes: if they start a UTF-8 sequence that
        ' continues past the chunk, end the chunk just before it.
        cut = 0
        for k = 1 to 3
            b = m.ba[length - k]
            if b < &h80 then exit for
            if b >= &hC0 then
                need = 2
                if b >= &hE0 then need = 3
                if b >= &hF0 then need = 4
                if need > k then cut = k
                exit for
            end if
        end for
        if cut > 0 then
            length = length - cut
            m.ba.ReadFile(m.path, m.pos, length)
        end if
    end if
    m.pos = m.pos + length
    return m.ba.ToAsciiString()
end function
