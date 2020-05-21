import org.w3c.dom.ls.LSOutput;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/*
 *  Represents a participant in the consensus algorithm. Participants communicate with the coordinator and each other.
 *  Using a CoordinatorCommsThread to communicate with the coordinator.
 *  Each participant has a port number associated for communication with other participants, which will be used as the
 *  address of the participant ServerSocket, used to connect to all other participants and communicate directly
 */
public class Participant {
    int coordPort;
    int loggerPort;
    int commsPort;
    int timeout;
    volatile int numConnected;
    volatile List<Integer> participantPorts;
    volatile List<String> voteOptions;
    volatile List<Vote> votes;

    //  The final outcome of the voting process
    volatile String finalOutcome;

    volatile List<PrintWriter> participantOutputs;

    volatile List<Vote> newVotes;

    //  The choice that this participant will vote for
    volatile String voteChoice;


    final Object coordThreadMonitor;
    final Object listenerMonitor;
    CountDownLatch listenerLatch;

    ServerSocket participantServer;


    volatile boolean choiceMade;
    volatile boolean votingComplete;

    public Participant(int coordPort, int loggerPort, int commsPort,int timeout) {
        this.timeout = timeout;
        this.commsPort = commsPort;
        this.coordPort = coordPort;
        this.loggerPort = loggerPort;
        this.participantPorts = new ArrayList<>();
        this.voteOptions = new ArrayList<>();
        this.votes = new ArrayList<>();
        this.participantOutputs = new ArrayList<>();
        this.newVotes = new ArrayList<>();
        coordThreadMonitor = new Object();
//        serverThreadsMonitor = new Object();
        listenerMonitor = new Object();
        this.listenerLatch = new CountDownLatch(0);

        this.finalOutcome = "";
        this.voteChoice = "";
        this.choiceMade = false;
        this.votingComplete = false;
        this.numConnected = 0;

    }



    public void initParticipantServer() throws IOException{
        participantServer = new ServerSocket(this.commsPort);
        new Thread(() -> initServerLoop(participantServer)).start();
    }

    private void initServerLoop(ServerSocket participantServer) {
        while (serverLoopCheck()) {
            Socket participant = null;

            try {
                participant = participantServer.accept();
                participantOutputs.add(new PrintWriter( new OutputStreamWriter(participant.getOutputStream())));

            } catch (IOException e) {
                e.printStackTrace();
            }
            synchronized (this) {
                numConnected++;
            }
        }

        System.out.println("All other participants have connected to " + this.commsPort);
    }

    private synchronized boolean serverLoopCheck() {
        //  while the participant ports list hasn't been initialized or the number of connected clients is less than the size of the participant ports list - 1
        if (participantPorts.size() == 0 || numConnected < participantPorts.size() - 1) return true;
        else return false;
    }

    private synchronized void setVoteOptions(OptionsMessage msg) {
        this.voteOptions = msg.getOptions();
        decideVoteChoice();
    }

    private synchronized void decideVoteChoice() {
        if (this.voteOptions.size() == 0)
            throw new IllegalStateException("Empty list of voting options! Can't pick one");
        if (!this.voteChoice.isEmpty())
            throw new IllegalStateException("Voting choice has already been set! Can't choose again");
        Random random = new Random();
        int index = random.nextInt((this.voteOptions.size()));
        this.voteChoice = this.voteOptions.get(index);
        this.votes.add(new Vote(this.commsPort, voteChoice));
        this.choiceMade = true;
        this.notifyAll();

    }


    /*
     *  All participants should have initialized server sockets by now, so this initializes connections with the server
     *  sockets present at each thread.
     */
    private synchronized void setParticipantDetails(DetailsMessage details) {
        this.participantPorts = details.getPorts();
        this.listenerLatch = new CountDownLatch(this.participantPorts.size() - 1);
        connectToAllParticipants();
    }

    private void connectToAllParticipants() {
        this.participantPorts.forEach(port -> {
            if (port != this.commsPort)
                connectToPort(port);
        });
    }

    private synchronized void connectToPort(Integer portNum) {
        try {
            Socket coordinatorSocket = new Socket("localhost", portNum);
            new ListenerThread(coordinatorSocket).start();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void connectToCoordinator() throws IOException {
            Socket coordinatorSocket = new Socket("localhost", this.coordPort);
            new CoordinatorCommsThread(coordinatorSocket, this.listenerLatch).start();

    }

    private List<Integer> getSavedVotePorts() {
        List<Integer> savedVotePorts = new ArrayList<>();
        for (Vote vote: this.votes) {
            savedVotePorts.add(vote.getParticipantPort());
        }
        return savedVotePorts;
    }

    private List<Vote> fromVoteMessage(VoteMessage voteMessage) {
        List<Vote> msgVotes = new ArrayList<>();
        for (Map.Entry<Integer,String> vote: voteMessage.getVotes().entrySet()) {
            msgVotes.add(new Vote(vote.getKey(),vote.getValue()));
        }
        return msgVotes;
    }

    private synchronized void processVoteMessage(VoteMessage voteMsg) {
        List<Vote> receivedVotes = fromVoteMessage(voteMsg);
        for (Vote vote: receivedVotes) {
            if (!getSavedVotePorts().contains(vote.getParticipantPort())) {
                newVotes.add(vote);
                this.votes.add(vote);
            }
        }
    }


    private void sendMessage(PrintWriter outWriter,String msg) {
        outWriter.println(msg);
        outWriter.flush();
    }

    public static void main(String[] args) throws IOException {
        if (args.length < 4 ) throw new IllegalArgumentException("Can't have less than 4 args!");

        int coordPort = Integer.parseInt(args[0]);
        int loggerPort = Integer.parseInt(args[1]);
        int partPort = Integer.parseInt(args[2]);
        int partTimeout = Integer.parseInt(args[3]);

        Participant participant = new Participant(coordPort,loggerPort,partPort,partTimeout);
        participant.initParticipantServer();
        participant.connectToCoordinator();

        synchronized (participant) {
            while (!participant.choiceMade) {
                try {
                    participant.wait();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }

        //  notified so voting begins
        participant.startVoting();
    }

    private synchronized String generateVoteMessage() {
        if (this.newVotes.size() == 0) return "";
        else {
            String msg = "VOTE";
            for (Vote newVote: newVotes) {
                msg += " " + newVote.getParticipantPort() + " " + newVote.getVote();
            }
            newVotes.clear();
            return msg;
        }
    }

    public void startVoting() {
        /*
         *  Repeat for the number of voting rounds:
         *  For each round, contruct the voting message and send it out using the map <integer,printwriter>.
         *  At the same time, receive the voting messages from the participantClientThreads
         *  (can notify the client threads of a new round to receive their input for that round and only that input)
         *  If number of received messages == no. other participants or timeout period reached finalize the received input
         */
        synchronized (listenerMonitor) {
            listenerMonitor.notifyAll();
        }
        broadcastMessage("VOTE " + this.commsPort + " " + this.voteChoice);
        System.out.println("Voting has begun for participant " + this.commsPort
                + " and the first vote message has been sent. My choice: " + this.voteChoice);

        try {
            this.listenerLatch.await(timeout, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        System.out.println("First round of voting successfully complete for participant " + this.commsPort
                + ". Votes received: " + printAllVotes());

        String voteMessage = generateVoteMessage();

        //  remaining voting rounds
        for (int i = 1; i < this.participantPorts.size(); i++) {
            synchronized (listenerMonitor) {
                listenerMonitor.notifyAll();
            }

            broadcastMessage(voteMessage);
            System.out.println("Participant " + this.commsPort + "sent out vote message " + voteMessage + " for round " + (i+1) );
            try {
                this.listenerLatch.await(timeout, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            //  All listener threads finished their round if await passed

            try {
                Thread.sleep(timeout);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }

            voteMessage = generateVoteMessage();
        }

        decideFinalOutcome();
        endVoting();
    }

    private String printAllVotes() {
        String votes = "";
        for (Vote vote : this.votes) {
            votes += vote.toString();
        }

        return votes;
    }

    private synchronized void endVoting() {
        if (this.finalOutcome.isEmpty())
            throw new IllegalStateException("Tried to end the voting but an outcome hasn't been decided!");

        this.votingComplete = true;

        System.out.println("Voting has ended on participant " + this.commsPort + ". Final outcome: " + this.finalOutcome);

        synchronized (coordThreadMonitor) {
            coordThreadMonitor.notifyAll();
        }

        try {
            this.participantServer.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private synchronized void decideFinalOutcome() {
        Map<String,Integer> optionFreq = new HashMap<>();

        for (String option: this.voteOptions) {
            optionFreq.put(option,0);
        }

        for (Vote vote: this.votes) {
            optionFreq.replace(vote.getVote(), (optionFreq.get(vote.getVote()) + 1));
        }

        int largestFreq = 0;

        for (Integer i: optionFreq.values()) {
            if (i > largestFreq) largestFreq = i;
        }

        List<String> winningOptions = new ArrayList<>();

        for (String option: optionFreq.keySet()) {
            if (optionFreq.get(option) == largestFreq) winningOptions.add(option);
        }

        Collections.sort(winningOptions);

        this.finalOutcome = winningOptions.get(0);
    }

    private synchronized void broadcastMessage(String msg) {
        this.participantOutputs.forEach(pw -> sendMessage(pw,msg));
    }


    private class ListenerThread extends Thread {
        Socket otherServer;
        BufferedReader inpReader;
        PrintWriter outWriter;

        public ListenerThread(Socket participantSocket) throws IOException {
            this.otherServer = participantSocket;
            //  buffered reader for string input from coordinator
            this.inpReader = new BufferedReader( new InputStreamReader( participantSocket.getInputStream() ) );

            //  print writer for string output to coordinator
            this.outWriter = new PrintWriter( new OutputStreamWriter( participantSocket.getOutputStream() ) );

            outWriter.flush();
        }

        @Override
        public void run() {
            String recMsg = "";
            try {
                //  waits for voting to start
                synchronized (listenerMonitor) {
                    listenerMonitor.wait();
                }

                while(!votingComplete) {
                    //start timer here, which interrupts the bufferedreader
                    //  will probably use the countdownlatch instead
                    recMsg = inpReader.readLine();

                    if (recMsg != null && !recMsg.isEmpty()) {
                        VoteMessage recVote = (VoteMessage) MsgParser.parseMessage(recMsg);
                        processVoteMessage(recVote);
                    }

                    listenerLatch.countDown();

                    synchronized (listenerMonitor) {
                        listenerMonitor.wait();
                    }
                }

                otherServer.close();

            } catch (IOException e) {
                e.printStackTrace();
            } catch (InterruptedException ie) {
                ie.printStackTrace();
            }
        }
    }

    private class CoordinatorCommsThread extends Thread {
        Socket coordinatorSocket;
        CountDownLatch listenerLatch;
        BufferedReader inpReader;
        PrintWriter outWriter;

        public CoordinatorCommsThread(Socket coordinatorSocket, CountDownLatch listenerLatch) throws IOException {
            this.coordinatorSocket = coordinatorSocket;
            //  buffered reader for string input from coordinator
            this.inpReader = new BufferedReader( new InputStreamReader( coordinatorSocket.getInputStream() ) );

            //  print writer for string output to coordinator
            this.outWriter = new PrintWriter( new OutputStreamWriter( coordinatorSocket.getOutputStream() ) );

            this.listenerLatch = listenerLatch;

            outWriter.flush();
        }
 
        @Override
        public void run() {
            sendMessage(outWriter, "JOIN " + commsPort);
            String recMsg = "";


            try {
                recMsg = inpReader.readLine();

                DetailsMessage detMsg = (DetailsMessage) MsgParser.parseMessage(recMsg);

                setParticipantDetails(detMsg);

                recMsg = inpReader.readLine();

                OptionsMessage optMsg = (OptionsMessage) MsgParser.parseMessage(recMsg);

                setVoteOptions(optMsg);

                waitForVotingToEnd();

                System.out.println("Coord comms thread notified so voting should have ended by now");

                sendMessage(outWriter,createFinalOutcomeMessage());

                System.out.println("Participant " + commsPort + " sent final outcome to coordinator, closing socket");
                coordinatorSocket.close();

            } catch (IOException ioe) {
                ioe.printStackTrace();
            }
        }
    }

    private String createFinalOutcomeMessage() {
        if (this.finalOutcome.isEmpty())
            throw new IllegalStateException("Shouldn't call this method until the final outcome is decided!");

        String msg = "OUTCOME";
        msg += " " + this.finalOutcome;
        for (Vote vote : this.votes) {
            msg += " " + vote.getParticipantPort();
        }

        return msg;
    }

    private void waitForVotingToEnd() {
        synchronized (coordThreadMonitor) {
            while (!votingComplete) {
                try {
                    coordThreadMonitor.wait();
                } catch (InterruptedException ie) {
                    ie.printStackTrace();
                }
            }
        }
    }
}
