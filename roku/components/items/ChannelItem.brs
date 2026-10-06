sub init()
    m.bg = m.top.findNode("bg")
    m.num = m.top.findNode("num")
    m.name = m.top.findNode("name")
    m.now = m.top.findNode("now")
    m.barBg = m.top.findNode("barBg")
    m.bar = m.top.findNode("bar")
    m.star = m.top.findNode("star")
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
    m.name.text = c.title
    m.now.text = c.description
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
        if f > 0.5 then
            m.bar.color = "0xFFFFFFFF"
        else
            m.bar.color = "0x2F80FFFF"
        end if
    else
        m.bg.opacity = f * 0.25
        m.bar.color = "0x2F80FFFF"
    end if
end sub
