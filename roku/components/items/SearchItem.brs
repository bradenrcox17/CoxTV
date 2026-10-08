sub init()
    m.bg = m.top.findNode("bg")
    m.title = m.top.findNode("title")
    m.subLabel = m.top.findNode("sub")
end sub

sub onContent()
    c = m.top.itemContent
    if c = invalid then return
    m.title.text = c.title
    if c.description <> "" then
        m.subLabel.text = c.shortDescriptionLine1 + "   |   " + c.description
    else
        m.subLabel.text = c.shortDescriptionLine1
    end if
end sub

sub onFocus()
    if m.top.listHasFocus then
        m.bg.opacity = m.top.focusPercent
    else
        m.bg.opacity = m.top.focusPercent * 0.25
    end if
    paintRowText(m.top.listHasFocus and m.top.focusPercent > 0.5)
end sub

' Focused rows are orange with dark text; the normal colors come back when focus leaves.
sub paintRowText(focused as boolean)
    if m.baseColors = invalid then
        m.baseColors = {}
        for each id in ["title", "sub"]
            n = m.top.findNode(id)
            if n <> invalid then m.baseColors[id] = n.color
        end for
    end if
    for each id in m.baseColors
        if focused then m.top.findNode(id).color = "0x0A0A0BFF" else m.top.findNode(id).color = m.baseColors[id]
    end for
end sub

