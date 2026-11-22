package com.gamepaper.tictactoe;

import java.util.UUID;

record PendingGomokuInvitation(UUID inviterId, UUID targetId) {
}
