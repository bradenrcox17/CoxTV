sub init()
    m.bg = m.top.findNode("bg")
    m.moveBg = m.top.findNode("moveBg")
    m.box = m.top.findNode("box")
    m.tick = m.top.findNode("tick")
    m.name = m.top.findNode("name")
    m.right = m.top.findNode("right")
    m.observed = invalid
end sub

sub onContent()
    ' Rows are moved by swapping data between nodes, so re-render whenever any of it changes.
    if m.observed <> invalid then
        for each f in ["title", "checked", "moving"]
            m.observed.unobserveFieldScoped(f)
        end for
    end if
    c = m.top.itemContent
    m.observed = c
    if c = invalid then return
    for each f in ["title", "checked", "moving"]
        c.observeFieldScoped(f, "render")
    end for
    render()
end sub

sub render()
    c = m.top.itemContent
    if c = invalid then return
    m.name.text = c.title
    m.right.text = c.shortDescriptionLine1
    m.box.visible = c.showCheck
    m.tick.visible = c.checked
    m.moveBg.visible = c.moving
    if c.showCheck then
        m.name.translation = [84, 0]
    else
        m.name.translation = [24, 0]
    end if
end sub

sub onFocus()
    if m.top.listHasFocus then
        m.bg.opacity = m.top.focusPercent
    else
        m.bg.opacity = 0
    end if
end sub
