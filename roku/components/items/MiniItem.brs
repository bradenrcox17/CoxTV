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
    if m.top.listHasFocus then
        m.bg.opacity = m.top.focusPercent
    else
        m.bg.opacity = 0
    end if
end sub
