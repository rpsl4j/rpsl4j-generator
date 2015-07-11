/*
 * Copyright (c) 2015 Benjamin Roberts, Nathan Kelly, Andrew Maxwell
 * All rights reserved.
 */

package org.rpsl4j.emitters.rpsldocument;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.commons.lang3.tuple.Pair;

import com.google.common.collect.LinkedHashMultimap;
import com.google.common.collect.Multimap;

import net.ripe.db.whois.common.rpsl.AttributeType;
import net.ripe.db.whois.common.rpsl.ObjectType;
import net.ripe.db.whois.common.rpsl.RpslAttribute;
import net.ripe.db.whois.common.rpsl.RpslObject;
import net.ripe.db.whois.common.rpsl.attrs.AttributeParseException;
import net.ripe.db.whois.common.rpsl.attrs.AutNum;

/**
 * BGPAutNum corresponds to the aut-num class in RPSL. This class is responsible for parsing
 * the aut-num class and generating the tables of routes it's policy dictates to export.
 * @author Benjamin George Roberts
 */
public class BGPAutNum {
	private RpslObject rpslObject;
	private AutNum autNumObject;
	protected String name;
	protected long autNum;

	/**
	 * Maps of (AS-Peer, IP) -> [Routes].
	 * Used to store the list of routes included for export to BGP peers
	 */
	protected Multimap<Pair<Long, String>, BGPRoute> includedRouteMap;

	/**
	 * Initialise a new BGPAutNum instance and generate its route maps
	 * @param object aut-num RPSL object
	 */
	public BGPAutNum(RpslObject object) {
		//Sanity check the provided object
		if(object.getType() != ObjectType.AUT_NUM) throw new IllegalArgumentException("Requires AUT_NUM object, got " + object.getType());
		rpslObject = object;

		//This parse method can throw an error. We can't really recover so we won't catch it
		autNumObject = AutNum.parse(rpslObject.getTypeAttribute().getCleanValue());
		autNum = autNumObject.getValue();

		//Again, this is a mandatory attribute, assume it exists
		name = rpslObject.getValueForAttribute(AttributeType.AS_NAME).toString();

		//Jump into building the route maps
		generateRouteMaps();
	}

	/**
	 * Generate the peer route maps by parsing the AutNum RPSL object's export attributes.
	 */
	private void generateRouteMaps() {
		Multimap<Pair<Long, String>, BGPRoute> includedRouteMap = LinkedHashMultimap.create();
		for(RpslAttribute attr : rpslObject.getAttributes()) {
			//Skip non export attributes
			//TODO do we need to include EXPORT_VIA?
			if(attr.getType() != AttributeType.EXPORT)
				continue;

			//Get the Tuples of ((PeerAS, PeerIP), LocalRouter) from the export attribute
			Set<Pair<Pair<Long, String>, String>> exportPeers = getExportPeers(attr);

			//Resolve the routes for the provided peer and corresponding local router, and add to the route map
			for(Pair<Pair<Long, String>, String> exportPeer : exportPeers) {
				//TODO ignores actions, need to for each peer
				includedRouteMap.putAll(exportPeer.getLeft(), BGPRoute.resolveRoutes(attr, exportPeer.getRight()));
			}
		}

		//Update the object's maps
		this.includedRouteMap = includedRouteMap;
	}

	/**
	 * Returns a set of triples, each containing an AS/IP pair, and the source of the exported route.
	 * Example: to AS1 1.1.1.1 2.2.2.2 at 3.3.3.3 would return [((AS1, 1.1.1.1), 3.3.3.3), ((AS2, 2.2.2.2), 3.3.3.3)]
	 * @param attr export attribute to retrieve peers from
	 * @return Set of tuples of the form [((AS, PeerIP), LocalRouter)]
	 */
	static Set<Pair<Pair<Long, String>, String>> getExportPeers(RpslAttribute attr) {
		//Sanity check parameter
		if(attr.getType() != AttributeType.EXPORT) throw new IllegalArgumentException("Requires EXPORT attribtue, got " + attr.getType());
		Set<Pair<Pair<Long, String>, String>> exportPeers = new HashSet<Pair<Pair<Long, String>, String>>();
		List<Pair<String, List<String>>> attrAST = attr.getTokenList();

		//Iterate through peering specifications and add peers to set
		for(int i = 0; i < attrAST.size(); i++) {
			Pair<String, List<String>> currentASTPair = attrAST.get(i);
			List<String> pairRight = currentASTPair.getRight();
			String localRouter;
			long autNum;
			
			//Filter entries that aren't peering specifications
			if(!currentASTPair.getLeft().equals("to"))
				continue;
			//Check that the peering specification has a valid local router (next entry is at with 1 address)
			if(i < (attrAST.size() + 1) &&
					(!attrAST.get(i+1).getLeft().equals("at") ||
					 attrAST.get(i+1).getRight().size() != 1)) {
				System.err.println("Malformed peering specification: " + attr.toString());
				continue;
			}

			//Check that list contains an ASN and extract it
			try {
				if(pairRight.size() < 1)
					throw new AttributeParseException("Missing peering specification", attr.toString());

				AutNum peerAN = AutNum.parse(pairRight.get(0));
				autNum = peerAN.getValue();
			} catch (AttributeParseException e) {
				System.err.println(e.getMessage());
				continue;
			}

			//TODO in this section we assume that all subsequent values are valid IP's, this might not be true

			//Get the local router address
			localRouter = attrAST.get(i+1).getRight().get(0);

			//Check if it we're exporting to the entire AS (no peer routers)
			if(pairRight.size() < 2) {
				//Add the default peer
				exportPeers.add(Pair.of(Pair.of(autNum, "0.0.0.0"), localRouter));
			} else {
				//Add a peer for each peer address
				for(int j = 1; j < pairRight.size(); j++)
					exportPeers.add(Pair.of(Pair.of(autNum, pairRight.get(j)), localRouter));
			}
		}
		return exportPeers;
	}

	/**
	 * Query the AutNum object to retrieve it's export routing table for a peer.
	 * @param peerAutNum AS of the peer to be retrieved (eg. 1)
	 * @param peerAddress Address of the peer to be retrieved (eg. "1.1.1.1")
	 * @return table of routes exported to peer
	 */
	public BGPRouteTable getTableForPeer(long peerAutNum, String peerAddress) {
		return new BGPRouteTable(peerAutNum, peerAddress, this);
	}

	/**
	 * Query the AutNum object to retrieve it's export routing table for all peers in an AS
	 * @param peerAutNum AS to be retrieved (eg. 1)
	 * @return table of routes exported to all peers in provided AS
	 */
	public BGPRouteTable getTableForAS(long peerAutNum) {
		return new BGPRouteTable(peerAutNum, this);
	}

	/**
	 * Query the AutNum object to find which AS a particular peer address is from.
	 * This is accomplished by searching for the first route table matching the address.
	 * @param peerAddress peer to search for
	 * @return AS of peer (eg. 1) or -1 if not declared
	 */
	public long getASOfPeer(String peerAddress) {
		//Iterate through entries to find first with matching IP address
		for(Pair<Long, String> peerEntry : includedRouteMap.keySet()) {
			if(peerEntry.getRight().equals(peerAddress))
				return peerEntry.getLeft();
		}
		return -1;
	}

	@Override
	public String toString() {
		return String.format("%s (AS%s)", name, autNum);
	}
}