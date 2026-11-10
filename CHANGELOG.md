# Changelog

All notable changes to the Pink LSZ plugin since it was published to the RuneLite Plugin Hub (1.0.0).

## 2.0.1

- New "Connect to the clan site" setting, off by default, with RuneLite's third-party warning. Nothing is sent to pinkosrs.com until it is turned on. While it is off the panel says so and one chat line appears each login.
### Added
- **Clan bingo.**
  - **View Board** button on bingo events (under "Ends in") opens a pop-out window with your team's board and progress. It refreshes every minute, shows each tile's picture, points and "got / need", and has a **View on website** link.
  - Hover a tile that needs several items to see the list. Click it to see what the team already has, who got each item and when, and what is left. Free tiles say **FREE**.
  - Live bingo events also appear in the Events list, with your team's name and colour.
  - **Speed tiles:** a finished time that beats a tile's target time takes a screenshot, which is kept on the tile as backup proof for staff.
- **Events tab** next to TeamFinder: clan events that are live or coming up, and your past attendance.
  - For events that track attendance, just take part. The plugin adds up your time in the event automatically and shows **Attendance confirmed** when it counts. While you are not counting yet it tells you what is missing ("Join world 373", "Go into the raid with your team", "Go into the Wilderness", "Stay close to other clan members", and so on).
  - Countdown shows days once an event is more than 24 hours away.
  - **Teachers and learners:** on the day of a teachable event you can tick **I can teach at this event**, or tick that you are still learning, so teachers are credited. Staff confirm teachers before they count.
  - **I'm hosting this event** tick box for staff, so members see the host.
  - **Event kills:** during a tracked event the plugin sends player kills (who killed whom), for PK trips and tournaments such as Emir's Arena. They are only kept if they fall inside an event.
- **Available for a team.** Press **I'm available** with an activity, team size and Split / FFA choice and you show up in **Players available**, with your clan rank icon. Hosts get an **Invite** button on players who match their team. An invite appears at the top of your panel with a **Join** row. New setting **Notify me of team invites** sends a RuneLite notification with the host's world and region.
- **Clan rank icons** next to names in TeamFinder (teams, players available, host crown), the same pictures the dashboard shows.
- **In raid** status on a team while its host is inside the raid.
- **Gear for the DPS calculator:** your combat stats, worn gear, inventory and rune pouch are sent so the clan site's DPS calculator can load them. Setting: **Share gear for the DPS calculator**.
- The whole **collection log** is read when you open it, and each new log item is sent as you get it.
- Deaths at the **Fortis Colosseum** count as Sol Heredit, and dying to the Ferox Enclave PvP tutor counts as Pete Kayer.

### Changed
- **Screenshots are only taken when they are needed.** For every finished time, a personal best or not, the plugin first asks the site whether it would take 1st or 2nd on the clan record, or beat a bingo speed tile. Only then is the screenshot taken (retried up to 12 times). Everything else is sent without an image.
- Raid room and boss times (Theatre of Blood, Fractured Archive) are kept per team size, so each room has its own record for solo, 2 players and so on.
- A team no longer closes when its host leaves the raid. It stays until the host closes it, and joining stays open.
- Teams can be hosted on the Duel Arena tournament world (558) as well as normal members worlds.
- Routine chat lines ("Connected", "Sent your saved drops...") are gone. The plugin only speaks up when something is wrong.

### Fixed
- Pets no longer count as what killed you in a death.
- A time that would take 1st or 2nd in an empty team-size slot no longer goes to staff without a screenshot.

## 1.1.1

### Added
- **Barbarian Assault** in the TeamFinder activity list (2 to 5 players).
- **Split / FFA** tick box when creating a team. Every team shows "Split" or "FFA" next to the team size and world.
- **Team started notice:** when someone creates a team, everyone running the plugin gets one chat line ("X has started a ... team on world N. Join them from the Pink LSZ icon in your RuneLite toolbar."). Shown as plain text with no prefix (only the player's name is pink). The host does not get their own. New setting **Show TeamFinder alerts** (on by default) turns these off without affecting clan announcements.
- Team alerts are checked every 8 seconds (was 20), so they arrive faster, and read "X has started a Corp 4p team on w350. Join them from the clan plugin" (team size or "mass" included).
- Creating a team with a Party Hub puts the host in that party too.
- Barbarian Assault and Zalcano have no Split / FFA choice.
- **Party Hub** field when creating a team. Pressing **Join** also puts you straight into that RuneLite party (the one the Party Panel plugin shows). The passphrase is only sent to players who are in the team.

### Fixed
- Spectating someone else's raid, Fractured Archive or Colosseum run no longer reports their time as yours. Group times are only sent if you hit something or were hit during the run.
- The collection log message counted four items twice (the game lists them under two ids), so it could say 701 when the game's own count was 697. It now matches the game.
- A world hop or loading screen no longer counts as being away: the plugin keeps checking in, so only a real log out of 45 seconds or more removes you from a team.
- When you are taken out of a team, the chat line now says why (away for 45 seconds, the host closed it, or a moderator removed it).

### Changed
- No more "Sent a drop to the dashboard" chat line for every drop. Drops are still sent, quietly.
- Team size is stricter: the game's own "Team size" line is always used first, the Colosseum is always solo, and counting players in the instance is only a last resort. A count is sent marked as counted, so staff are told to check it, and a silly count is never sent. (Also moved off the deprecated `client.getPlayers()`.)
- Raid room and boss times (Theatre of Blood, Fractured Archive) that are not a personal best get a screenshot only when they would take 1st or 2nd on the clan record, so staff can check them.

## 1.1.0

### Added
- **TeamFinder side panel** (clan icon in the RuneLite sidebar) for group content.
  - Create a team for any group activity: Chambers of Xeric (incl. Challenge Mode), Theatre of Blood (Normal / Hard), Tombs of Amascut (with invocation level up to 600), The Fractured Archive, Nex, General Graardor, K'ril Tsutsaroth, Commander Zilyana, Kree'arra, Royal Titans (max 2 players), Yama, Sarachnis, The Hueycoatl, Corporeal Beast, Nightmare and Zalcano.
  - Team size can be set per activity, with a **Mass** option (more than 8) on the large-group bosses.
  - Add a short note (for example "learner friendly, KC/gear requirements") and see other teams' notes, world, mode and players.
  - Join, leave or close a team from the panel. Starting or joining a team leaves any other team you were in.
  - Teams are also posted to the clan Discord and updated live as players join and leave.
  - Clan moderators can remove a team from the panel.
  - The panel refreshes automatically (every 5 seconds while open or while you are in a team).
- **Team chat notices:** a game-chat line when you join a team, when someone joins or leaves your team, and when the team is full ("Team full (4/4): Time to go! Hop to world 301!").
- **Away handling:** a player whose plugin has been silent for 45 seconds (logged out or closed the game) is removed from the team automatically.
- **Host hand-over:** when the host leaves or goes away, the next player to have joined becomes the host (with a chat line telling everyone) and the team only closes when nobody is left.
- **Clan announcements:** messages leaders send from the dashboard appear in your chatbox in the Pink LSZ colour. Delivery is confirmed by the plugin once the message is actually shown, so a message is never lost if you were loading or logged out. New setting: **Show clan announcements** (on by default).
- **Hide Side Panel** setting (off by default) to remove the TeamFinder button from the sidebar. Everything else keeps working.

### Changed
- The three login messages (kill counts, saved drop totals, combat achievements) are now a single line: "Sent your saved Drops, KCs & CAs to the Clan Dashboard."
- The default dashboard address is `https://pinkosrs.com`. An address saved from an older version (`dashboard.pinkosrs.com`) is switched over automatically.
- The side panel uses larger, clearer text and a rounder, more modern layout.
- The activity list loads by itself after you log in (no manual refresh needed).

### Fixed
- Theatre of Blood room times (Maiden, Bloat, Nylocas, Sotetseg, Xarpus, Verzik; Normal and Hard Mode) and Fractured Archive boss times are sent as their own records, using the room's duration (never the raid total).
- Individual raid room times (for example Fractured Archive rooms) and other ordinary times that have no kill-count line are no longer dropped: they are filed under the name in the game message.
- Team size for Theatre of Blood, Tombs of Amascut, The Fractured Archive, Chambers of Xeric and the Colosseum is taken from the game's own "Team size" line (even when it is a separate message), or worked out from the players in the instance when it is missing.
- Death tracking no longer reports a death for pet-related messages, Grotesque Guardians, or the Fortis Colosseum. All Colosseum NPCs are now treated as one fight.

### Security
- Every TeamFinder and announcement request is checked on the server: the character must be a current Pink LSZ member and match the game account the plugin first reported.
- Per-player hourly limits on creating, joining, leaving and closing teams, a short pause between creating teams, and a cap on open teams, to stop spam.

## 1.0.0
- First release on the RuneLite Plugin Hub: sends drops, kill counts, collection log, combat achievements, personal bests and deaths to the clan dashboard.
