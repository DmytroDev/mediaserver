package org.mobicents.media.server.impl.resource.ss7;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.SelectionKey;

import javolution.util.FastSet;

import org.apache.log4j.Logger;
import org.mobicents.ss7.linkset.oam.LinksetExecutor;
import org.mobicents.ss7.linkset.oam.LinksetManager;
import org.mobicents.ss7.management.console.Subject;
import org.mobicents.ss7.management.transceiver.ChannelProvider;
import org.mobicents.ss7.management.transceiver.ChannelSelectionKey;
import org.mobicents.ss7.management.transceiver.ChannelSelector;
import org.mobicents.ss7.management.transceiver.Message;
import org.mobicents.ss7.management.transceiver.MessageFactory;
import org.mobicents.ss7.management.transceiver.ShellChannel;
import org.mobicents.ss7.management.transceiver.ShellServerChannel;

public class ShellExecutor {

    Logger logger = Logger.getLogger(ShellExecutor.class);

    private ChannelProvider provider;
    private ShellServerChannel serverChannel;
    private ShellChannel channel;
    private ChannelSelector selector;
    private ChannelSelectionKey skey;

    private MessageFactory messageFactory = null;

    private String rxMessage = "";
    private String txMessage = "";

    private LinksetManager linksetManager = null;
    private LinksetExecutor linksetExec = null;

    private volatile boolean started = false;

    private String address;

    private int port;

    public ShellExecutor() throws IOException {

    }

    public String getAddress() {
        return address;
    }

    public void setAddress(String address) {
        this.address = address;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public LinksetManager getLinksetManager() {
        return linksetManager;
    }

    public void setLinksetManager(LinksetManager linksetManager) {
        this.linksetManager = linksetManager;
    }

    public void start() throws IOException {
        linksetExec = new LinksetExecutor();
        linksetExec.setLinksetManager(this.linksetManager);

        provider = ChannelProvider.provider();
        serverChannel = provider.openServerChannel();
        InetSocketAddress inetSocketAddress = new InetSocketAddress(address, port);
        serverChannel.bind(inetSocketAddress);

        selector = provider.openSelector();
        skey = serverChannel.register(selector, SelectionKey.OP_ACCEPT);

        messageFactory = ChannelProvider.provider().getMessageFactory();

        this.logger.info(String.format("ShellExecutor listening at %s", inetSocketAddress));

        this.started = true;
    }

    public void stop() {
        this.started = false;

        try {
            skey.cancel();
            if (channel != null) {
                channel.close();
            }
            serverChannel.close();
            selector.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void perform() {

        if (started) {
            try {
                FastSet<ChannelSelectionKey> keys = selector.selectNow();

                for (FastSet.Record record = keys.head(), end = keys.tail(); (record = record.getNext()) != end;) {
                    ChannelSelectionKey key = (ChannelSelectionKey) keys.valueOf(record);

                    if (key.isAcceptable()) {
                        accept();
                    } else if (key.isReadable()) {
                        ShellChannel chan = (ShellChannel) key.channel();
                        Message msg = (Message) chan.receive();

                        if (msg != null) {
                            System.out.println("Receive " + msg);
                            rxMessage = msg.toString();

                            if (rxMessage.compareTo("disconnect") == 0) {
                                this.txMessage = "Bye";
                                chan.send(messageFactory.createMessage(txMessage));

                            } else {
                                String[] options = rxMessage.split(" ");
                                Subject subject = Subject.getSubject(options[0]);
                                if (subject == null) {
                                    chan.send(messageFactory.createMessage("Invalid Subject"));
                                } else {
                                    // Nullify examined options
                                    options[0] = null;

                                    switch (subject) {
                                    case LINKSET:
                                        this.txMessage = this.linksetExec.execute(options);
                                        chan.send(messageFactory.createMessage(this.txMessage));
                                        break;
                                    }
                                }
                            } // if (rxMessage.compareTo("disconnect")
                        } // if (msg != null)

                        // TODO Handle message

                        rxMessage = "";

                    } else if (key.isWritable() && txMessage.length() > 0) {

                        if (this.txMessage.compareTo("Bye") == 0) {
                            this.closeChannel();
                        }
                        this.txMessage = "";
                    }
                }
            } catch (IOException e) {
                logger.error("Error while operating on ChannelSelectionKey", e);
            }

        }

    }

    private void accept() throws IOException {
        channel = serverChannel.accept();
        skey.cancel();
        skey = channel.register(selector, SelectionKey.OP_READ | SelectionKey.OP_WRITE);
        System.out.println("Client connected");
    }

    private void closeChannel() throws IOException {
        if (channel != null) {
            try {
                this.channel.close();
            } catch (IOException e) {
                logger.error("Error closing channel", e);
            }
        }
        skey.cancel();
        skey = serverChannel.register(selector, SelectionKey.OP_ACCEPT);
    }
}
