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
    if m.mode = "teams" then
        m.title.text = "Favorite teams"
        m.hint.text = ""
        m.empty.text = "Loading..."
        m.teamQuery = ""
        m.teamsChanged = false
        m.favReply = svcCall({ type: "teamCatalog" }, "onTeamCatalog")
        m.global.bus.observeFieldScoped("prefsVersion", "onTeamsSyncedIn") ' teams changed on another device
        m.top.observeField("closed", "onClosed")
    else if m.mode = "hidden" then
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
    if m.mode = "teams" then
        onTeamSelected(i)
    else if m.mode = "hidden" then
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
    if m.mode = "teams" then
        if key = "back" then return teamsBack()
        if key = "options" then
            if m.teamSport <> invalid then openTeamSearch()
            return true
        end if
        return false
    end if
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

' ---------------------------------------------------------------- favorite teams
' Settings > Favorite teams: a list of sports, then a sport's teams (yours first, then A-Z).
' OK stars or un-stars a team; the row stays put. "Find a team" (or *) searches by name.

sub onTeamCatalog(event as object)
    if m.favReply = invalid or not event.getRoSGNode().isSameNode(m.favReply) then return
    m.favReply = invalid
    sports = event.getData().sports
    m.teamCatalogOk = (type(sports) = "roArray" and sports.Count() > 0)
    if not m.teamCatalogOk then
        sports = []
        for each s in [["nfl", "NFL"], ["ncaaf", "College (all NCAA sports)"], ["nba", "NBA"], ["wnba", "WNBA"], ["mlb", "MLB"], ["nhl", "NHL"], ["soccer", "Soccer"]]
            sports.Push({ id: s[0], label: s[1], teams: [] })
        end for
    end if
    m.teamSports = sports
    showTeamSports(0)
end sub

sub showTeamSports(focusIndex as integer)
    m.teamSport = invalid
    m.title.text = "Favorite teams"
    m.hint.text = "OK: choose a sport, then star your teams. Their games are marked and listed first in Sports on now," + chr(10) + "and sync with your other TVs, phone and tv.thecoxhome.com. College teams count in every NCAA sport."
    mine = favoriteTeams()
    root = CreateObject("roSGNode", "ContentNode")
    for each s in m.teamSports
        lst = mine[s.id]
        names = []
        if type(lst) = "roArray" then
            for each t in lst
                names.Push(t.display)
            end for
        end if
        title = s.label
        if names.Count() > 0 then title = title + "   -   " + names.Join(", ")
        right = "No teams yet"
        if names.Count() = 1 then right = "1 team"
        if names.Count() > 1 then right = names.Count().ToStr() + " teams"
        addRow(root, s.id, title, right, false, false)
    end for
    showContent(root, focusIndex)
end sub

function teamSportById(id as string) as dynamic
    for each s in m.teamSports
        if s.id = id then return s
    end for
    return invalid
end function

sub showTeams(id as string)
    s = teamSportById(id)
    if s = invalid then return
    m.teamSport = id
    m.title.text = s.label
    if id = "ncaaf" then
        m.hint.text = "OK: star or un-star a school (ticked = your team).  *: find a school by name." + chr(10) + "A school counts in every NCAA sport: football, basketball, baseball and more."
    else
        m.hint.text = "OK: star or un-star a team (ticked = your team).  *: find a team by name."
    end if
    q = m.teamQuery
    words = []
    for each w in q.Split(" ")
        if w <> "" then words.Push(LCase(w))
    end for
    mine = favoriteTeams()[id]
    if type(mine) <> "roArray" then mine = []
    starred = {}
    rows = []
    for each t in mine
        starred[t.key] = true
        rows.Push(t)
    end for
    for each t in s.teams
        if not starred.DoesExist(t.key) then rows.Push(t)
    end for
    first = []
    rest = []
    for each t in rows
        name = LCase(t.display)
        ok = true
        for each w in words
            if Instr(1, name, w) = 0 then ok = false
        end for
        if ok then
            ' Names that start with the search first ("tenn": Tennessee before East Tennessee State).
            if words.Count() > 0 and Left(name, Len(words[0])) = words[0] and not starred.DoesExist(t.key) then first.Push(t) else rest.Push(t)
        end if
    end for
    found = []
    for each t in rest
        if starred.DoesExist(t.key) then found.Push(t)
    end for
    found.Append(first)
    for each t in rest
        if not starred.DoesExist(t.key) then found.Push(t)
    end for
    root = CreateObject("roSGNode", "ContentNode")
    if q = "" then
        if id = "ncaaf" then addRow(root, "__search__", "Find a school...", "* or OK", false, false) else addRow(root, "__search__", "Find a team...", "* or OK", false, false)
    else
        addRow(root, "__search__", "Find: " + q, "OK to change", false, false)
    end if
    for each t in found
        addTeamRow(root, t, starred.DoesExist(t.key), "")
    end for
    if found.Count() = 0 and teamKey(q) <> "" then
        ' Not in the list (or no list without a setup code): offer the typed name itself.
        addTeamRow(root, { key: teamKey(q), display: q }, false, "Add " + Chr(34) + q + Chr(34))
    end if
    if root.getChildCount() = 1 and q = "" and not m.teamCatalogOk then
        if regRead("deviceToken") = "" then
            addRow(root, "__none__", "The team list comes from tv.thecoxhome.com: set this Roku up with a code first.", "", false, false)
        else
            addRow(root, "__none__", "The team list isn't available right now. Use Find a team to add one by name.", "", false, false)
        end if
    end if
    showContent(root, 0)
    m.list.jumpToItem = 0
end sub

sub addTeamRow(root as object, t as object, on as boolean, label as string)
    title = t.display
    if label <> "" then title = label
    right = ""
    if on then right = "Your team"
    addRow(root, t.key, title, right, on, true)
    root.getChild(root.getChildCount() - 1).description = t.display
end sub

sub onTeamSelected(i as integer)
    node = m.list.content.getChild(i)
    if node = invalid then return
    if m.teamSport = invalid then
        m.teamQuery = ""
        m.teamSportIndex = i
        showTeams(node.id)
        return
    end if
    if node.id = "__search__" then
        openTeamSearch()
        return
    end if
    if node.id = "__none__" then return
    on = not node.checked
    setTeamIn(m.teamSport, { key: node.id, display: node.description }, on)
    m.teamsChanged = true
    node.checked = on
    if on then
        node.shortDescriptionLine1 = "Your team"
        node.title = node.description
    else
        node.shortDescriptionLine1 = ""
    end if
end sub

sub openTeamSearch()
    dlg = CreateObject("roSGNode", "StandardKeyboardDialog")
    if dlg = invalid then dlg = CreateObject("roSGNode", "KeyboardDialog") ' Roku OS < 10
    if m.teamSport = "ncaaf" then dlg.title = "Find a school" else dlg.title = "Find a team"
    dlg.text = m.teamQuery
    dlg.buttons = ["Search", "Show all", "Cancel"]
    dlg.observeFieldScoped("buttonSelected", "onTeamSearchButton")
    dlg.observeFieldScoped("wasClosed", "onTeamSearchClosed")
    m.dialog = dlg
    m.top.getScene().dialog = dlg
end sub

sub onTeamSearchButton()
    dlg = m.dialog
    if dlg = invalid then return
    b = dlg.buttonSelected
    if b = 0 then m.teamQuery = dlg.text.Trim()
    if b = 1 then m.teamQuery = ""
    dlg.unobserveFieldScoped("buttonSelected")
    dlg.unobserveFieldScoped("wasClosed")
    m.dialog = invalid
    dlg.close = true
    m.top.getScene().dialog = invalid
    if b <= 1 then showTeams(m.teamSport)
    m.list.setFocus(true)
end sub

sub onTeamSearchClosed()
    if m.dialog = invalid then return
    m.dialog.unobserveFieldScoped("buttonSelected")
    m.dialog.unobserveFieldScoped("wasClosed")
    m.dialog = invalid
    m.top.getScene().dialog = invalid
    m.list.setFocus(true)
end sub

' Back from a sport's teams: the list of sports (with the new teams), and sync right away.
function teamsBack() as boolean
    if m.teamSport = invalid then
        if m.teamsChanged = true then svcCall({ type: "syncNow" }, "onTeamsSynced")
        return false
    end if
    if m.teamsChanged = true then
        svcCall({ type: "syncNow" }, "onTeamsSynced")
        m.teamsChanged = false
    end if
    i = m.teamSportIndex
    if i = invalid then i = 0
    showTeamSports(i)
    return true
end function

sub onTeamsSynced()
end sub

' Teams synced in from another device: refresh the sports (or tick marks; rows stay put).
sub onTeamsSyncedIn()
    if m.teamSports = invalid or m.list.content = invalid then return
    if m.teamSport = invalid then
        showTeamSports(m.list.itemFocused)
        return
    end if
    mine = favoriteTeams()[m.teamSport]
    keys = {}
    if type(mine) = "roArray" then
        for each t in mine
            keys[t.key] = true
        end for
    end if
    content = m.list.content
    for i = 0 to content.getChildCount() - 1
        node = content.getChild(i)
        if node.showCheck then
            on = keys.DoesExist(node.id)
            if node.checked <> on then
                node.checked = on
                if on then node.shortDescriptionLine1 = "Your team" else node.shortDescriptionLine1 = ""
            end if
        end if
    end for
end sub

sub onClosed()
    if m.mode = "teams" then m.global.bus.unobserveFieldScoped("prefsVersion")
end sub
