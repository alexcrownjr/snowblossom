package snowblossom.miner;

import com.google.protobuf.ByteString;
import duckutil.Config;
import duckutil.ConfigFile;
import duckutil.TimeRecord;
import duckutil.TimeRecordAuto;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.stub.StreamObserver;
import snowblossom.lib.*;
import snowblossom.proto.*;
import snowblossom.proto.UserServiceGrpc.UserServiceBlockingStub;
import snowblossom.proto.UserServiceGrpc.UserServiceStub;
import snowblossom.lib.trie.HashUtils;
import snowblossom.lib.SnowMerkleProof;

import io.grpc.Server;
import io.grpc.ServerBuilder;


import java.io.File;
import java.io.FileInputStream;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.text.DecimalFormat;
import java.util.Collections;
import java.util.LinkedList;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

public class MrPlow
{
  private static final Logger logger = Logger.getLogger("snowblossom.miner");

  public static void main(String args[]) throws Exception
  {
    Globals.addCryptoProvider();
    if (args.length != 1)
    {
      logger.log(Level.SEVERE, "Incorrect syntax. Syntax: MrPlow <config_file>");
      System.exit(-1);
    }

    ConfigFile config = new ConfigFile(args[0]);

    LogSetup.setup(config);


    MrPlow miner = new MrPlow(config);

    while (true)
    {
      Thread.sleep(15000);
      miner.printStats();
    }
  }

  private volatile Block last_block_template;

  private UserServiceStub asyncStub;
  private UserServiceBlockingStub blockingStub;

  private final NetworkParams params;

  private AtomicLong op_count = new AtomicLong(0L);
  private long last_stats_time = System.currentTimeMillis();
  private Config config;

  private TimeRecord time_record;
  private MiningPoolServiceAgent agent;

  public MrPlow(Config config) throws Exception
  {
    this.config = config;
    logger.info(String.format("Starting MrPlow version %s", Globals.VERSION));

    config.require("node_host");
    config.require("pool_address");

    params = NetworkParams.loadFromConfig(config);

    if (config.getBoolean("display_timerecord"))
    {
      time_record = new TimeRecord();
      TimeRecord.setSharedRecord(time_record);
    }

    subscribe();

    int port = config.getIntWithDefault("mining_pool_port",23380);
    agent = new MiningPoolServiceAgent(this);

    Server s = ServerBuilder
      .forPort(port)
      .addService(agent)
      .build();

    s.start();
  }

  private ManagedChannel channel;

  private void subscribe() throws Exception
  {
    if (channel != null)
    {
      channel.shutdownNow();
      channel = null;
    }

    String host = config.get("node_host");
    int port = config.getIntWithDefault("node_port", params.getDefaultPort());
    channel = ManagedChannelBuilder.forAddress(host, port).usePlaintext(true).build();

    asyncStub = UserServiceGrpc.newStub(channel);
    blockingStub = UserServiceGrpc.newBlockingStub(channel);

    AddressSpecHash to_addr = getPoolAddress();

    CoinbaseExtras.Builder extras = CoinbaseExtras.newBuilder();
    if (config.isSet("remark"))
    {
      extras.setRemarks(ByteString.copyFrom(config.get("remark").getBytes()));
    }

    asyncStub.subscribeBlockTemplate(SubscribeBlockTemplateRequest.newBuilder().setPayRewardToSpecHash(to_addr.getBytes()).setExtras(extras.build()).build(),
                                     new BlockTemplateEater());
    logger.info("Subscribed to blocks");

  }

  private AddressSpecHash getPoolAddress() throws Exception
  {

      String address = config.get("pool_address");
      AddressSpecHash to_addr = new AddressSpecHash(address, params);
      return to_addr;
  }

  public void stop()
  {
    terminate = true;
  }

  private volatile boolean terminate = false;

  public NetworkParams getParams() {return params;}

  public UserServiceBlockingStub getBlockingStub(){return blockingStub;}

  public void printStats()
  {
    long now = System.currentTimeMillis();
    double count = op_count.getAndSet(0L);

    double time_ms = now - last_stats_time;
    double time_sec = time_ms / 1000.0;
    double rate = count / time_sec;

    DecimalFormat df = new DecimalFormat("0.000");

    String block_time_report = "";
    if (last_block_template != null)
    {
      BigInteger target = BlockchainUtil.targetBytesToBigInteger(last_block_template.getHeader().getTarget());

      double diff = PowUtil.getDiffForTarget(target);

      double block_time_sec = Math.pow(2.0, diff) / rate;
      double hours = block_time_sec / 3600.0;
      block_time_report = String.format("- at this rate %s hours per block", df.format(hours));
    }

    logger.info(String.format("Mining rate: %s/sec %s", df.format(rate), block_time_report));

    last_stats_time = now;

    if (count == 0)
    {
      logger.info("we seem to be stalled, reconnecting to node");
      try
      {
        subscribe();
      }
      catch (Throwable t)
      {
        logger.info("Exception in subscribe: " + t);
      }
    }

    if (config.getBoolean("display_timerecord"))
    {

      TimeRecord old = time_record;

      time_record = new TimeRecord();
      TimeRecord.setSharedRecord(time_record);

      old.printReport(System.out);

    }
  }

  public Block getBlockTemplate()
  {
    return last_block_template;
  }



  public class BlockTemplateEater implements StreamObserver<Block>
  {
    public void onCompleted() {}

    public void onError(Throwable t) {}

    public void onNext(Block b)
    {
      logger.info("Got block template: height:" + b.getHeader().getBlockHeight() + " transactions:" + b.getTransactionsCount());

      last_block_template = b;
      agent.updateBlockTemplate(b);
    }
  }
}
