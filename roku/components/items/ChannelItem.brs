sub init()
    m.bg = m.top.findNode("bg")
    m.num = m.top.findNode("num")
    m.name = m.top.findNode("name")
    m.now = m.top.findNode("now")
    m.barBg = m.top.findNode("barBg")
    m.bar = m.top.findNode("bar")
    m.star = m.top.findNode("star")
    m.mineBg = m.top.findNode("mineBg")
    m.focused = false
    m.observed = invalid
end sub

sub onContent()
    ' Watch the bound node so now-playing / favorite updates repaint without rebinding.
    if m.observed <> invalid then
        m.observed.unobserveFieldScoped("description")
        m.observed.unobserveFieldScoped("starRating")
    end if
    c = m.top.itemContent
    m.observed = c
    if c = invalid then return
    c.observeFieldScoped("description", "render")
    c.observeFieldScoped("starRating", "render")
    render()
end sub

sub render()
    c = m.top.itemContent
    if c = invalid then return
    m.num.text = c.shortDescriptionLine2
    ' A live game for one of your teams ("Your team  -  League  -  title"): the matchup is the
    ' title, with an orange "LIVE  ★ Your team" line under it.
    mine = Left(c.description, 14) = "Your team  -  "
    m.mineBg.visible = mine
    if mine then
        rest = Mid(c.description, 15)
        cut = Instr(1, rest, "  -  ")
        league = ""
        title = rest
        if cut > 0 then
            league = Left(rest, cut - 1)
            title = Mid(rest, cut + 5)
        end if
        sides = teamSides(title)
        if sides.Count() = 2 then m.name.text = sides[0] + " vs " + sides[1] else m.name.text = title
        m.now.text = "LIVE    " + Chr(9733) + " Your team    " + league + "  " + Chr(8226) + "  " + c.title
    else
        m.name.text = c.title
        m.now.text = c.description
    end if
    m.mine = mine
    paintRow()
    if c.playStart > 0 and c.length > c.playStart then
        frac = (nowSecs() - c.playStart) / (c.length - c.playStart)
        if frac < 0 then frac = 0
        if frac > 1 then frac = 1
        m.bar.width = 400 * frac
        m.bar.visible = true
        m.barBg.visible = true
    else
        m.bar.visible = false
        m.barBg.visible = false
    end if
    m.star.visible = c.starRating >= 100
end sub

sub onFocus()
    f = m.top.focusPercent
    if m.top.listHasFocus then
        m.bg.opacity = f
        m.focused = f > 0.5
    else
        m.bg.opacity = f * 0.25
        m.focused = false
    end if
    paintRow()
end sub

' Text, bar and star colors for the row's state: dark on the orange focus bar; otherwise
' normal, with the "Your team" line in orange.
sub paintRow()
    if m.focused = true then
        dark = "0x0A0A0BFF"
        m.num.color = dark
        m.name.color = dark
        m.now.color = dark
        m.bar.color = dark
        m.star.blendColor = dark
    else
        m.num.color = "0xA3A3A8FF"
        m.name.color = "0xEDEDEDFF"
        if m.mine = true then m.now.color = "0xFF8200FF" else m.now.color = "0xC2C2C6FF"
        m.bar.color = "0xFF8200FF"
        m.star.blendColor = "0xFF8200FF"
    end if
end sub
