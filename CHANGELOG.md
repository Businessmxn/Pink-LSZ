# Changelog

All notable changes to the Pink LSZ plugin since it was published to the RuneLite Plugin Hub (1.0.0).

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
- A world hop or loading screen no longer counts as being away: the plugin keeps checking in, so only a real log out of 45 seconds or more removes you from a team.
- When you are taken out of a team, the chat line now says why (away for 45 seconds, the host closed it, or a moderator removed it).

### Changed
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
