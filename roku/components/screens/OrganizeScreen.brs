' Settings > Categories: choose which categories the sidebar shows and their order.
' Settings > Reorder favorites: the order of the Favorites list (and of channel up/down in it).
'
' Moving a row: "pick it up", then the row follows the list's focus as you press Up / Down
' (its data is swapped along with the focus), and OK or Back drops it and saves.

sub init()
    m.list = m.top.findNode("list")
    m.title = m.top.findNode("title")
    m.hint = m.top.findNode("hint")
    m.empty = m.top.findNode("empty")
    m.status = m.top.findNode("status")
    m.list.observeField("itemSelected", "onSelected")
    m.list.observeField("itemFocused", "onFocused")
    m.top.observeField("params", "onParams")
    m.top.observeField("active", "onActive")
    m.mode = "categories"
    m.moving = -1        ' index of the row being moved, or -1
    m.enabledCount = 0   ' categories: rows [0, enabledCount) are the ones shown
    m.favReply = invalid
end sub

sub onParams()
    p = m.top.params
    if p.mode <> invalid then m.mode = p.mode
    if m.mode = "hidden" then
        m.title.text = "Hidden channels"
        m.hint.text = "OK: show a hidden channel again (OK again hides it). Hide channels with * in a channel list." + chr(10) + "Hidden channels don't appear in lists, the guide, search or Sports on now."
        m.empty.text = "Loading..."
        m.favReply = svcCall({ type: "hiddenList" }, "onHiddenList")
    else if m.mode = "favorites" then
        m.title.text = "Reorder favorites"
        m.hint.text = "OK: pick up a channel, move it with Up / Down, then OK to drop it.    *: remove from favorites." + chr(10) + "Channel up / down in the player follows this order."
        m.empty.text = "Loading..."
        m.favReply = svcCall({ type: "list", category: "__fav__" }, "onFavList")
    else
        m.title.text = "Categories"
        m.hint.text = "OK: show or hide a category in the sidebar.    *: move a shown category with Up / Down, then OK to drop it." + chr(10) + "Shown categories are listed first, in sidebar order. New categories from your provider start hidden."
        buildCategories(0)
    end if
end sub

sub onActive()
    if m.top.active then m.list.setFocus(true)
end sub

sub addRow(root as object, key as string, title as string, right as string, checked as boolean, showCheck as boolean)
    n = root.CreateChild("ContentNode")
    n.addFields({ checked: checked, moving: false, showCheck: showCheck })
    n.setFields({ id: key, title: title, shortDescriptionLine1: right })
end sub

sub showContent(root as object, focusIndex as integer)
    m.list.content = root
    n = root.getChildCount()
    m.empty.visible = (n = 0)
    if focusIndex >= n then focusIndex = n - 1
    if focusIndex > 0 then m.list.jumpToItem = focusIndex
end sub

' ---------------------------------------------------------------- categories

sub buildCategories(focusIndex as integer)
    counts = {}
    counts.SetModeCaseSensitive()
    groups = []
    cats = m.global.bus.categories
    if cats <> invalid then
        for each c in cats
            counts[c.name] = c.count
            groups.Push(c.name)
        end for
    end if
    enabled = []
    enabledSet = {}
    enabledSet.SetModeCaseSensitive()
    for each k in categoryOrder()
        if (isBuiltInCategory(k) or counts.DoesExist(k)) and not enabledSet.DoesExist(k) then
            enabled.Push(k)
            enabledSet[k] = true
        end if
    end for
    root = CreateObject("roSGNode", "ContentNode")
    for each k in enabled
        addRow(root, k, categoryLabel(k), countText(k, counts), true, true)
    end for
    builtIns = ["__fav__", "__recent__", "__sports__", "__cfb__", "__all__"]
    for each league in sportsLeagues()
        builtIns.Push("__league__:" + league)
    end for
    for each k in builtIns
        if not enabledSet.DoesExist(k) then addRow(root, k, categoryLabel(k), countText(k, counts), false, true)
    end for
    for each k in groups
        if not enabledSet.DoesExist(k) then addRow(root, k, k, countText(k, counts), false, true)
    end for
    m.enabledCount = enabled.Count()
    showContent(root, focusIndex)
end sub

function countText(key as string, counts as object) as string
    if key = "__fav__" then
        n = favKeys().Count()
    else if key = "__recent__" then
        n = recentKeys().Count()
    else if key = "__sports__" then
        return "live games now"
    else if key = "__cfb__" then
        return "this week's games"
    else if Left(key, 11) = "__league__:" then
        return "live games"
    else if key = "__all__" then
        n = m.global.bus.channelCount
    else
        n = counts[key]
    end if
    if n = invalid then return ""
    return n.ToStr() + " channels"
end function

sub toggleCategory(i as integer)
    node = m.list.content.getChild(i)
    if node = invalid then return
    order = categoryOrder()
    if node.checked then
        if m.enabledCount <= 1 then
            m.status.text = "At least one category has to stay on."
            return
        end if
        kept = []
        for each k in order
            if k <> node.id then kept.Push(k)
        end for
        setCategoryOrder(kept)
        buildCategories(i)
    else
        order.Push(node.id)
        setCategoryOrder(order)
        ' The row joins the end of the shown list; keep focus on the row after the one pressed.
        buildCategories(i + 1)
    end if
end sub

' ---------------------------------------------------------------- favorites

sub onFavList(event as object)
    if m.favReply = invalid or not event.getRoSGNode().isSameNode(m.favReply) then return
    m.favReply = invalid
    items = event.getData().items
    root = CreateObject("roSGNode", "ContentNode")
    if items <> invalid then
        for each r in items
            addRow(root, r[0], r[5] + "    " + r[1], r[4], false, false)
        end for
    end if
    m.empty.text = "No favorites yet." + chr(10) + "Press * on a channel to add it."
    showContent(root, 0)
end sub

sub removeFavorite(i as integer)
    content = m.list.content
    node = content.getChild(i)
    if node = invalid then return
    toggleFavorite(node.id)
    content.removeChildIndex(i)
    n = content.getChildCount()
    m.empty.visible = (n = 0)
    if n > 0 then
        if i >= n then i = n - 1
        m.list.jumpToItem = i
    end if
end sub

' ---------------------------------------------------------------- moving

sub startMove(i as integer)
    node = m.list.content.getChild(i)
    if node = invalid then return
    m.moving = i
    node.moving = true
    m.status.text = "Moving " + node.title.Trim() + ":  Up / Down to move, OK to drop."
end sub

sub onFocused()
    if m.moving < 0 then return
    j = m.list.itemFocused
    last = m.list.content.getChildCount() - 1
    if m.mode = "categories" then last = m.enabledCount - 1 ' stay among the shown categories
    if j > last then j = last
    if j < 0 then j = 0
    while m.moving < j
        swapRows(m.moving, m.moving + 1)
        m.moving = m.moving + 1
    end while
    while m.moving > j
        swapRows(m.moving, m.moving - 1)
        m.moving = m.moving - 1
    end while
    if m.list.itemFocused <> j then m.list.jumpToItem = j
end sub

sub swapRows(a as integer, b as integer)
    content = m.list.content
    na = content.getChild(a)
    nb = content.getChild(b)
    va = { id: na.id, title: na.title, shortDescriptionLine1: na.shortDescriptionLine1, checked: na.checked, moving: na.moving }
    vb = { id: nb.id, title: nb.title, shortDescriptionLine1: nb.shortDescriptionLine1, checked: nb.checked, moving: nb.moving }
    na.setFields(vb)
    nb.setFields(va)
end sub

sub dropMove()
    content = m.list.content
    node = content.getChild(m.moving)
    if node <> invalid then node.moving = false
    m.moving = -1
    m.status.text = ""
    if m.mode = "categories" then
        visible = []
        seen = {}
        seen.SetModeCaseSensitive()
        for i = 0 to m.enabledCount - 1
            k = content.getChild(i).id
            visible.Push(k)
            seen[k] = true
        end for
        ' Categories missing from the current playlist keep their place at the end.
        for each k in categoryOrder()
            if not seen.DoesExist(k) then visible.Push(k)
        end for
        setCategoryOrder(visible)
    else
        ordered = []
        present = {}
        present.SetModeCaseSensitive()
        for i = 0 to content.getChildCount() - 1
            k = content.getChild(i).id
            ordered.Push(k)
            present[k] = true
        end for
        ' Favorites this playlist doesn't have stay put; the visible ones fill their own slots.
        out = []
        q = 0
        for each k in favKeys()
            if present.DoesExist(k) and q < ordered.Count() then
                out.Push(ordered[q])
                q = q + 1
            else
                out.Push(k)
            end if
        end for
        setFavKeys(out)
    end if
end sub

sub onSelected()
    i = m.list.itemSelected
    m.status.text = ""
    if m.mode = "hidden" then
        toggleHiddenRow(i)
    else if m.moving >= 0 then
        dropMove()
    else if m.mode = "favorites" then
        startMove(i)
    else
        toggleCategory(i)
    end if
end sub

function onKeyEvent(key as string, press as boolean) as boolean
    if not press then return false
    if key = "back" and m.moving >= 0 then
        dropMove()
        return true
    end if
    if key = "options" and m.mode = "hidden" then return true
    if key = "options" then
        i = m.list.itemFocused
        if m.moving >= 0 then
            dropMove()
        else if m.mode = "favorites" then
            removeFavorite(i)
        else
            node = m.list.content.getChild(i)
            if node <> invalid and node.checked then
                startMove(i)
            else
                m.status.text = "Turn a category on (OK) before moving it."
            end if
        end if
        return true
    end if
    return false
end function

' ---------------------------------------------------------------- hidden channels

sub onHiddenList(event as object)
    if m.favReply = invalid or not event.getRoSGNode().isSameNode(m.favReply) then return
    m.favReply = invalid
    items = event.getData().items
    root = CreateObject("roSGNode", "ContentNode")
    if items <> invalid then
        for each r in items
            addRow(root, r[0], r[1], "Hidden", false, false)
        end for
    end if
    m.empty.text = "No hidden channels." + chr(10) + "Press * on a channel in a list to hide it."
    showContent(root, 0)
end sub

' Rows stay in place (marked) so the list doesn't jump while you go through it.
sub toggleHiddenRow(i as integer)
    node = m.list.content.getChild(i)
    if node = invalid then return
    if node.shortDescriptionLine1 = "Hidden" then
        setHidden(node.id, false)
        node.shortDescriptionLine1 = "Shown again"
    else
        setHidden(node.id, true)
        node.shortDescriptionLine1 = "Hidden"
    end if
end sub
