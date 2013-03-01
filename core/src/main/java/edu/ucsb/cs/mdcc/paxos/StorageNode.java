package edu.ucsb.cs.mdcc.paxos;

import java.nio.ByteBuffer;
import java.util.*;

import edu.ucsb.cs.mdcc.Option;
import edu.ucsb.cs.mdcc.config.MDCCConfiguration;
import edu.ucsb.cs.mdcc.config.Member;
import edu.ucsb.cs.mdcc.dao.Database;
import edu.ucsb.cs.mdcc.dao.InMemoryDatabase;
import edu.ucsb.cs.mdcc.dao.Record;
import edu.ucsb.cs.mdcc.messaging.MDCCCommunicator;

import edu.ucsb.cs.mdcc.messaging.ReadValue;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

public class StorageNode extends Agent {

    private static final Log log = LogFactory.getLog(StorageNode.class);

    private Database db = new InMemoryDatabase();
	private Map<String, Set<Option>> transactions = new HashMap<String, Set<Option>>();
    private MDCCConfiguration config;

    private MDCCCommunicator communicator;

    public StorageNode() {
        this.config = MDCCConfiguration.getConfiguration();
        this.communicator = new MDCCCommunicator();
    }

    @Override
    public void start() {
        super.start();
        int port = config.getLocalMember().getPort();
        communicator.startListener(this, port);

        //now we talk to everyone else to do recovery
        runRecoveryPhase();
    }

    private void runRecoveryPhase() {
        Map<String, Long> myVersions = new HashMap<String, Long>();
        Collection<Record> records = db.getAll();
        for (Record record : records) {
        	myVersions.put(record.getKey(), record.getVersion());
        }

        RecoverySet recoveryVersions = new RecoverySet(config.getMembers().length - 1);
        for (Member member : config.getMembers()) {
        	if (!member.isLocal()) {
        		communicator.sendRecoverAsync(member, myVersions, recoveryVersions);
        	}
        }

        Map<String, ReadValue> versions;
        while ((versions = recoveryVersions.dequeueRecoveryInfo()) != null) {
            log.info("Received recovery set");
            //replace our entries with any newer entries
            for (Map.Entry<String, ReadValue> entry : versions.entrySet()) {
                Record record = db.get(entry.getKey());
                if ((record.getVersion() == 0) ||
                        (entry.getValue().getVersion() > record.getVersion())) {
                    log.debug("recovered value for '" + entry.getKey() + "'");
                    ReadValue readValue = entry.getValue();
                    record.setValue(ByteBuffer.wrap(readValue.getValue()));
                    record.setVersion(readValue.getVersion());
                    record.setClassicEndVersion(readValue.getClassicEndVersion());
                    db.put(record);
                }
            }
        }
    }

    @Override
    public void stop() {
        super.stop();
        communicator.stopListener();
    }

    public boolean onAccept(Accept accept) {
		log.info("Received accept message: " + accept);

        String key = accept.getKey();
        BallotNumber ballot = accept.getBallotNumber();
        String transaction = accept.getTransactionId();
        long oldVersion = accept.getOldVersion();
        ByteBuffer value = accept.getValue();

        synchronized (accept.getKey().intern()) {
            if (ballot.isFastBallot() && db.get(key).isOutstanding()) {
                log.warn("Outstanding option detected on " + key +
                        " - Denying the new option");
                return false;
            }

            Record record = db.get(key);
            BallotNumber entryBallot = record.getBallot();

            long version = record.getVersion();
            //if it is a new insert
            boolean success = (version == oldVersion) &&
                    (ballot.isFastBallot() || ballot.compareTo(entryBallot) >= 0);

            if (success) {
                record.setOutstanding(true);
                db.put(record);
                if (!transactions.containsKey(transaction)) {
                    Set<Option> set = new TreeSet<Option>(new Comparator<Option>() {
                        public int compare(Option o1, Option o2) {
                            return o1.getKey().compareTo(o2.getKey());
                        }
                    });
                    transactions.put(transaction, set);
                }
                transactions.get(transaction).add(
                        new Option(key, value, record.getVersion(), false));
				log.info("option accepted");
            } else {
				log.warn("option denied");
            }
			return success;
		}
	}

	public void onDecide(String transaction, boolean commit) {
		if (commit) {
			log.info("Received Commit decision on transaction id: " + transaction);
        } else {
			log.info("Received Abort on transaction id: " + transaction);
        }

		if (commit && transactions.containsKey(transaction)) {
            for (Option option : transactions.get(transaction)) {
                Record record = db.get(option.getKey());
                record.setVersion(option.getOldVersion() + 1);
                record.setValue(option.getValue());
                record.setOutstanding(false);
                record.setOutstandingClassic(false);
                db.put(record);
                log.info("[COMMIT] Saved option to DB");
            }
		}
        transactions.remove(transaction);
    }

	public ReadValue onRead(String key) {
        Record record = db.get(key);
        return new ReadValue(record.getVersion(), record.getClassicEndVersion(),
                record.getValue());
	}

	public static void main(String[] args) {
        final StorageNode storageNode = new StorageNode();
        Runtime.getRuntime().addShutdownHook(new Thread() {
            @Override
            public void run() {
                storageNode.stop();
            }
        });
        storageNode.start();
	}

	public boolean onPrepare(Prepare prepare) {
        String key = prepare.getKey();
        BallotNumber ballotNumber = prepare.getBallotNumber();
        long classicEndVersion = prepare.getClassicEndVersion();

        Record record = db.get(key);
        BallotNumber existingBallot = record.getBallot();
        if (existingBallot.compareTo(ballotNumber) > 0) {
            return false;
        }

        record.setClassicEndVersion(classicEndVersion);
        db.put(record);
        return true;
	}

	public Map<String, ReadValue> onRecover(Map<String, Long> versions) {
		Map<String, ReadValue> newVersions = new HashMap<String, ReadValue>();
		log.debug("preparing recovery set");
		//add all the objects that the requester is outdated on
        Collection<Record> records = db.getAll();
        for (Record record : records) {
            if (!versions.containsKey(record.getKey()) ||
                    (record.getVersion() > versions.get(record.getKey()))) {
                ReadValue readValue = new ReadValue(record.getVersion(),
                        record.getClassicEndVersion(), record.getValue());
                newVersions.put(record.getKey(), readValue);
            }
        }
		return newVersions;
	}

	public boolean runClassic(String transaction, String key,
			long oldVersion, ByteBuffer value) {
        log.info("Requested classic paxos on key: " + key);
		Member leader = findLeader(key, false);
        log.info("Found leader (for key = " + key + ") : " + leader.getProcessId());
        Option option = new Option(key, value, oldVersion, true);
        boolean result;
        if (leader.isLocal()) {
            synchronized (this) {
                Record record = db.get(key);
                if (record.isOutstandingClassic()) {
                    log.info("Outstanding classic key found for: " + key);
                    return false;
                }
                record.setOutstandingClassic(true);
                db.put(record);
            }

            Member[] members = MDCCConfiguration.getConfiguration().getMembers();
            BallotNumber ballot = new BallotNumber(1, leader.getProcessId());
            Record record = db.get(key);
            if (!record.isPrepared()) {
                // run prepare
                log.info("Running prepare phase");
                record.setClassicEndVersion(record.getVersion() + 4);
                db.put(record);

                ClassicPaxosVoteListener prepareListener = new ClassicPaxosVoteListener();
                PaxosVoteCounter prepareVoteCounter = new PaxosVoteCounter(option, prepareListener);
                Prepare prepare = new Prepare(key, ballot, record.getClassicEndVersion());
                for (Member member : members) {
                    communicator.sendPrepareAsync(member, prepare, prepareVoteCounter);
                }

                if (prepareListener.getResult()) {
                    log.info("Prepare phase SUCCESSFUL");
                    record.setPrepared(true);
                    db.put(record);
                } else {
                    log.warn("Failed to run the prepare phase");
                    return false;
                }
            }

            ClassicPaxosVoteListener listener = new ClassicPaxosVoteListener();
            PaxosVoteCounter voteCounter = new PaxosVoteCounter(option, listener);
            log.info("Running accept phase");
            Accept accept = new Accept(transaction, ballot, option);
            for (Member member : members) {
                communicator.sendAcceptAsync(member, accept, voteCounter);
            }

            if (record.getVersion() == record.getClassicEndVersion()) {
                log.info("Done with the classic rounds - Reverting back to fast mode");
                record.setPrepared(false);
                db.put(record);
            }
            result = listener.getResult();
        } else {
            ClassicPaxosResultObserver observer = new ClassicPaxosResultObserver(option);
            communicator.runClassicPaxos(leader, transaction, option, observer);
            result = observer.getResult();
        }
        return result;
    }

}
