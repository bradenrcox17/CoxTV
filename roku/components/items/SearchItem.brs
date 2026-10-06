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
end sub
