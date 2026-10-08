sub init()
    m.bg = m.top.findNode("bg")
    m.num = m.top.findNode("num")
    m.name = m.top.findNode("name")
    m.now = m.top.findNode("now")
    m.star = m.top.findNode("star")
    m.observed = invalid
end sub

sub onContent()
    if m.observed <> invalid then m.observed.unobserveFieldScoped("starRating")
    c = m.top.itemContent
    m.observed = c
    if c = invalid then return
    c.observeFieldScoped("starRating", "render")
    render()
end sub

sub render()
    c = m.top.itemContent
    if c = invalid then return
    m.num.text = c.shortDescriptionLine2
    m.name.text = c.title
    m.now.text = c.description
    m.star.visible = c.starRating >= 100
end sub

sub onFocus()
    focused = false
    if m.top.listHasFocus then
        m.bg.opacity = m.top.focusPercent
        focused = m.top.focusPercent > 0.5
    else
        m.bg.opacity = 0
    end if
    paintRowText(focused)
    star = m.top.findNode("star")
    if focused then star.blendColor = "0x0A0A0BFF" else star.blendColor = "0xFF8200FF"
end sub

' Focused rows are orange with dark text; the normal colors come back when focus leaves.
sub paintRowText(focused as boolean)
    if m.baseColors = invalid then
        m.baseColors = {}
        for each id in ["num", "name", "now"]
            n = m.top.findNode(id)
            if n <> invalid then m.baseColors[id] = n.color
        end for
    end if
    for each id in m.baseColors
        if focused then m.top.findNode(id).color = "0x0A0A0BFF" else m.top.findNode(id).color = m.baseColors[id]
    end for
end sub

