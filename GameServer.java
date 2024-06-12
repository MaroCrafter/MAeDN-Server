import javax.websocket.*;
import javax.websocket.server.ServerEndpoint;
import java.util.*;
import org.glassfish.tyrus.server.Server;

@ServerEndpoint("/room")
public class GameServer {
    private static Map<String, Set<Session>> games = new HashMap<>(); // list of players in a room
    private static Map<String, Integer> playerReady = new HashMap<>(); // amount of players that a ready in a room, 4 = start the game
    private static Map<String, Map<Integer, Integer>> gameState = new HashMap<>(); // game state for each room
    private static Map<String, Map<String, Integer>> gameData = new HashMap<>(); // game data for each room (e.g. current player, available moves, etc.)
    private static Map<String, Map<Integer, Session>> playerColor = new HashMap<>(); // player color for each room

    @OnOpen // runs when a new client connects to the server
    public void onOpen(Session client) {
        System.out.println("Client connected: " + client.getId());
    }

    @OnMessage // runs when the server receives a message from a client
    public void onMessage(String message, Session client) {
        try {
            System.out.println("Received message from client: " + message);
            String[] parts = message.split(":", 2); // split the message into two parts: the command and the data
            String command = parts[0];
            String data = (parts.length > 1) ? parts[1] : "";

            switch (command) {
                case "create": // create a new room
                    String roomId = UUID.randomUUID().toString().substring(0, 6); // generate a random room ID
                    games.put(roomId, new HashSet<>()); // create a new room
                    playerReady.put(roomId, 0); // set the amount of players that are ready in that room to 0
                    Map<Integer, Integer> startState = new HashMap<>();
                    for (int i = 1; i < 17; i++) {
                        startState.put(i ,i); // set the starting position of each piece
                    }
                    gameState.put(roomId, startState); // set the game state for the room
                    client.getBasicRemote().sendText("created:" + roomId); // send the room ID to the client
                    games.get(roomId).add(client); // add the client to the room
                    break;
                case "join": // join an existing room
                     if (data != null && games.containsKey(data)) { // check if the room exists
                         if (games.get(data).size() == 4) { // check if the room is full, if yes send an error
                             client.getBasicRemote().sendText("error:roomfull");
                             return;
                         }
                        games.get(data).add(client); // add the client to the room
                        client.getBasicRemote().sendText("joined:" + data); // send the room ID to the client
                     } else {
                        client.getBasicRemote().sendText("error:roomnotfound"); // send an error if the room does not exist
                     }
                     break;
                case "ready": // set the player as ready (or not ready)
                    String currentRoom = null;
                    for (String room : games.keySet()) {
                        if (games.get(room).contains(client)) {
                            currentRoom = room;
                            if (data.equals("true")) { // check if the player wants to be ready or not ready and update the amount of players that are ready in the room accordingly
                                playerReady.put(room, playerReady.get(room) + 1);
                            } else {
                                playerReady.put(room, playerReady.get(room) - 1);
                            }
                        }
                    }
                    if (playerReady.get(currentRoom) == 4) { // start the game if all players are ready
                        gameData.put(currentRoom, new HashMap<>()); // create a new game data object for the room
                        gameData.get(currentRoom).put("currentPlayer", 1); // initialize variables for the game data
                        gameData.get(currentRoom).put("availableMoves", 0);
                        playerColor.put(currentRoom, new HashMap<>()); // create a new player color object for the room
                        int playerNumber = 1;
                        for (Session player : games.get(currentRoom)) { // assign a color to each player
                            player.getBasicRemote().sendText("start:" + playerNumber);
                            playerColor.get(currentRoom).put(playerNumber, player); // Assign a color to a player
                            playerNumber++;
                        }
                    }
                    break;
                case "visualroll": // send the visual roll to all players in the room (adds no rolls to the player)
                    for (Session player : games.get(getRoomIdFromSession(client))) {
                        if (player != client) {
                            player.getBasicRemote().sendText("visualroll:" + data);
                        }
                    }
                    break;
                case "nextplayer": // switch to the next player
                    int currentPlayer = gameData.get(getRoomIdFromSession(client)).get("currentPlayer");
                    if (playerColor.get(getRoomIdFromSession(client)).get(currentPlayer) == client) {
                        currentPlayer++;
                        if (currentPlayer > 4) {
                            currentPlayer = 1;
                        }
                        gameData.get(getRoomIdFromSession(client)).put("currentPlayer", currentPlayer);
                        for (Session player : games.get(getRoomIdFromSession(client))) {
                            if (player != client) {
                                player.getBasicRemote().sendText("nextplayer:" + currentPlayer);
                            }
                        }
                    }
                    break;
                case "pieceoutroll": // send the piece out roll to all players in the room (acts like roll but displays a different message)
                    for (Session player : games.get(getRoomIdFromSession(client))) {
                        if (player != client) {
                            player.getBasicRemote().sendText("pieceoutroll:" + data);
                        }
                    }
                    break;
                case "roll": // send the roll to all players in the room (will add moves)
                    gameData.get(getRoomIdFromSession(client)).put("availableMoves", Integer.parseInt(data)); // set the available moves for the current player
                    for (Session player : games.get(getRoomIdFromSession(client))) {
                        if (player != client) {
                            player.getBasicRemote().sendText("roll:" + data);
                        }
                    }
                    break;
                case "move": // move a piece
                    roomId = getRoomIdFromSession(client);
                    Map<Integer, Integer> state = gameState.get(roomId);
                    int availableMoves = gameData.get(roomId).get("availableMoves");
                    int pieceToMove = Integer.parseInt(data);
                    currentPlayer = gameData.get(roomId).get("currentPlayer");

                    // Get the color path of the current player
                    List<Integer> colorPath = getColorPath(currentPlayer);

                    // Find the index of the piece to move in the color path
                    int currentIndex = colorPath.indexOf(state.get(pieceToMove));

                    // Calculate the target index
                    int targetIndex = currentIndex + availableMoves;
                    int targetField = colorPath.get(targetIndex);
                    for (Map.Entry<Integer, Integer> entry : state.entrySet()) {
                        // check if the target field is occupied by another piece, if yes throw it out
                        if (entry.getValue() == targetField) {
                            // Move the piece back to its starting field
                            int startingField;
                            boolean fieldOccupied = true;
                            for (int i = 0; i < 4; i++) {
                                // Find the starting field of the piece
                                if (entry.getKey() >= 1 && entry.getKey() <= 4) {
                                    startingField = 1 + i; // Field between 1 and 4
                                } else if (entry.getKey() >= 5 && entry.getKey() <= 8) {
                                    startingField = 5 + i; // Field between 5 and 8
                                } else if (entry.getKey() >= 9 && entry.getKey() <= 12) {
                                    startingField = 9 + i; // Field between 9 and 12
                                } else {
                                    startingField = 13 + i; // Field between 13 and 16
                                }

                                fieldOccupied = false;
                                // check each starting field for the color if it's occupied or not
                                for (Map.Entry<Integer, Integer> checkEntry : state.entrySet()) {
                                    if (checkEntry.getValue() == startingField) {
                                        fieldOccupied = true;
                                        break;
                                    }
                                }

                                if (!fieldOccupied) {
                                    state.put(entry.getKey(), startingField);
                                    break;
                                }
                            }

                            if (fieldOccupied) {
                                //do nothing -> no action needed because the field is occupied, keep looking for an empty field
                            }

                            break;
                        }
                    }

                    // Perform the move
                    state.put(pieceToMove, targetField);
                    gameState.put(roomId, state);

                    // Convert the game state to a string
                    StringBuilder gameStateString = new StringBuilder();
                    for (Map.Entry<Integer, Integer> entry : state.entrySet()) {
                        gameStateString.append(entry.getKey()).append(":").append(entry.getValue()).append(",");
                    }

                    // Remove the trailing comma since the last piece does not need a comma because no new pieces will be added
                    if (gameStateString.length() > 0) {
                        gameStateString.setLength(gameStateString.length() - 1);
                    }

                    // Broadcast the game state to all players in the room
                    for (Session player : games.get(roomId)) {
                        if (player != client) {
                            player.getBasicRemote().sendText("move:" + gameStateString.toString());
                        }
                    }
                    break;
                case "movewin": // like move but finishes the game and does not check for occupied fields -> already checked client side
                    roomId = getRoomIdFromSession(client);
                    Map<Integer, Integer> state2 = gameState.get(roomId);
                    int availableMoves2 = gameData.get(roomId).get("availableMoves");
                    int pieceToMove2 = Integer.parseInt(data);
                    currentPlayer = gameData.get(roomId).get("currentPlayer");

                    // Get the color path of the current player
                    List<Integer> colorPath2 = getColorPath(currentPlayer);

                    // Find the index of the piece to move in the color path
                    int currentIndex2 = colorPath2.indexOf(state2.get(pieceToMove2));

                    // Calculate the target index
                    int targetIndex2 = currentIndex2 + availableMoves2;
                    int targetField2 = colorPath2.get(targetIndex2);
                    state2.put(pieceToMove2, targetField2);
                    gameState.put(roomId, state2);
                    StringBuilder gameStateString2 = new StringBuilder();
                    for (Map.Entry<Integer, Integer> entry : state2.entrySet()) {
                        gameStateString2.append(entry.getKey()).append(":").append(entry.getValue()).append(",");
                    }

                    // Remove the trailing comma
                    if (gameStateString2.length() > 0) {
                        gameStateString2.setLength(gameStateString2.length() - 1);
                    }

                    // Broadcast the game state to all players in the room
                    for (Session player : games.get(roomId)) {
                        if (player != client) {
                            player.getBasicRemote().sendText("movewin:" + gameStateString2.toString());
                        }
                    }
                    break;
                case "movepieceout": // move a piece out of the home field -> does not calculate the target field since moving out of the home always goes to the first field (which is the 5th one in the color path -> index 4)
                    roomId = getRoomIdFromSession(client);
                    state = gameState.get(roomId);
                    int pieceToMoveOut = Integer.parseInt(data);
                    currentPlayer = gameData.get(roomId).get("currentPlayer");
                    colorPath = getColorPath(currentPlayer);
                    targetField = colorPath.get(4); // set target field to the first field (which isn't home fields) of the color path
                    // throws out other pieces if the target field is occupied
                    for (Map.Entry<Integer, Integer> entry : state.entrySet()) {
                        if (entry.getValue() == targetField) {
                            // Move the piece back to its starting field
                            int startingField;
                            boolean fieldOccupied = true;
                            for (int i = 0; i < 4; i++) {
                                if (entry.getKey() >= 1 && entry.getKey() <= 4) {
                                    startingField = 1 + i; // Field between 1 and 4
                                } else if (entry.getKey() >= 5 && entry.getKey() <= 8) {
                                    startingField = 5 + i; // Field between 5 and 8
                                } else if (entry.getKey() >= 9 && entry.getKey() <= 12) {
                                    startingField = 9 + i; // Field between 9 and 12
                                } else {
                                    startingField = 13 + i; // Field between 13 and 16
                                }

                                fieldOccupied = false;
                                for (Map.Entry<Integer, Integer> checkEntry : state.entrySet()) {
                                    if (checkEntry.getValue() == startingField) {
                                        fieldOccupied = true;
                                        break;
                                    }
                                }

                                if (!fieldOccupied) {
                                    state.put(entry.getKey(), startingField);
                                    break;
                                }
                            }

                            if (fieldOccupied) {
                                //do nothing -> impossible to reach
                            }

                            break;
                        }
                    }

                    // Perform the move
                    state.put(pieceToMoveOut, targetField);
                    gameState.put(roomId, state);

                    // Convert the game state to a string
                    StringBuilder gameStateString3 = new StringBuilder();
                    for (Map.Entry<Integer, Integer> entry : state.entrySet()) {
                        gameStateString3.append(entry.getKey()).append(":").append(entry.getValue()).append(",");
                    }

                    // Remove the trailing comma
                    if (gameStateString3.length() > 0) {
                        gameStateString3.setLength(gameStateString3.length() - 1);
                    }

                    // Broadcast the game state to all players in the room
                    for (Session player : games.get(roomId)) {
                        if (player != client) {
                            player.getBasicRemote().sendText("movepieceout:" + gameStateString3.toString());
                        }
                    }
                    break;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @OnClose // runs when a client disconnects from the server
    public void onClose(Session client) {
        System.out.println("Client disconnected: " + client.getId());
        for (String room : games.keySet()) {
            if (games.get(room).contains(client)) { // terminates the game if a player disconnects since the game is not playable anymore without all players
                for (Session player : games.get(room)) {
                    try {
                        player.getBasicRemote().sendText("error:playerdisconnected");
                    } catch (Exception e) {
                        if (!(e instanceof IllegalStateException))
                            e.printStackTrace();
                    }
                }
                games.remove(room); // remove all data related to the room
                playerReady.remove(room);
                gameState.remove(room);
                gameData.remove(room);
                playerColor.remove(room);
            }
        }
    }

    public String getRoomIdFromSession(Session session) { // get the room ID from a session object
        for (Map.Entry<String, Set<Session>> entry : games.entrySet()) {
            if (entry.getValue().contains(session)) {
                return entry.getKey();
            }
        }
        return null; // Return null if the session is not found in any room
    }

    public List<Integer> getColorPath(int color) { //will return the path for the pieces of each color, starting at their first home position, going through the path and ending at their last finish position
        List<Integer> colorPathIndex = new ArrayList<>();
        int start, end, finishStart, finishEnd, startPath, endPath;
        if (color == 1) {
            start = 1;
            end = 4;
            finishStart = 17;
            finishEnd = 20;
            startPath = 33;
            endPath = 72;
        } else if (color == 2) {
            start = 5;
            end = 8;
            finishStart = 21;
            finishEnd = 24;
            startPath = 43;
            endPath = 42;
        } else if (color == 3) {
            start = 9;
            end = 12;
            finishStart = 25;
            finishEnd = 28;
            startPath = 53;
            endPath = 52;
        } else if (color == 4) {
            start = 13;
            end = 16;
            finishStart = 29;
            finishEnd = 32;
            startPath = 63;
            endPath = 62;
        } else {
            return colorPathIndex;
        }

        for (int i = start; i <= end; i++) { //add the home positions
            colorPathIndex.add(i);
        }

        for (int i = 1; i <= 72-32; i++) { //add the path positions
            int j = startPath + i - 1;
            if (j > 72) {
                //if the index is bigger than the size of the availablePositions list, add the position at the index - the size of the list + 32 (because the first 32 positions in availablePositions are home and finish positions)
                colorPathIndex.add((j - 72) + 32);
            } else {
                colorPathIndex.add(j);
            }
        }

        for (int i = finishStart; i <= finishEnd; i++) { //add the finish positions
            colorPathIndex.add(i);
        }
        return colorPathIndex;
    }

    public static void main(String[] args) {
        Server server = new Server("localhost", 8080, "/", null, GameServer.class); // create a new server object using the external library Tyrus
        try {
            server.start();
            System.out.println("Server started on ws://localhost:8080/");
            System.in.read(); // Keep server running until user presses a key
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            server.stop();
        }
    }
}
