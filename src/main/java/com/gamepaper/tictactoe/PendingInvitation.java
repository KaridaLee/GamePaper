package com.gamepaper.tictactoe;

import java.util.UUID;

record PendingInvitation(UUID inviterId, UUID targetId) {
}
