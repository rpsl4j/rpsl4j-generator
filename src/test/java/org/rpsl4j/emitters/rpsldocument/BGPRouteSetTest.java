/*
 * Copyright (c) 2015 Benjamin Roberts, Nathan Kelly, Andrew Maxwell
 * All rights reserved.
 */

package org.rpsl4j.emitters.rpsldocument;

import static org.junit.Assert.*;

import java.util.Set;

import net.ripe.db.whois.common.domain.CIString;
import net.ripe.db.whois.common.io.RpslObjectStringReader;
import net.ripe.db.whois.common.rpsl.attrs.AddressPrefixRange;

import org.junit.Test;

public class BGPRouteSetTest {

	@Test
	public void byRefMembersTest() {
		String  routeMaintainedMember 	= "route: 1.1.1.0/24\norigin: AS1\nmnt-by: MNTR-ONE\nmember-of: rs-set\n\n",
				routeMember				= "route: 1.1.2.0/24\norigin: AS1\nmember-of: rs-set\n\n",
				routeOrphan				= "route: 1.1.3.0/24\norigin: AS1\n\n",
				routeSetByRef			= "route-set: rs-set\nmbrs-by-ref: MNTR-ONE\n\n",
				routeSetAny				= "route-set: rs-set\nmbrs-by-ref: ANY\n\n",
				routeSetEmpty			= "route-set: rs-set";
		
		//Check that mbrs-by-ref with a maintainer only gets the maintained, member-of route
		BGPRpslDocument doc = BGPRpslDocument.parseRpslDocument(new RpslObjectStringReader(
				routeMaintainedMember + routeMember + routeOrphan + routeSetByRef));
		
		assertEquals("only routes with matching member-of and mnt-by should be added to set with restrictive mbrs-by-ref", 1,
				doc.routeSets.get("rs-set").resolve(doc).size());

		//double check it was the right one (I think I get the convoluted call award :L) 
		assertTrue(((BGPRpslRoute)doc.routeSets.get("rs-set").resolve(doc).iterator().next()).getMaintainer().equals(CIString.ciString("MNTR-ONE")));
		
		//Check that all routes with member-of are added to unrestricted set
		doc = BGPRpslDocument.parseRpslDocument(new RpslObjectStringReader(
				routeMaintainedMember + routeMember + routeOrphan + routeSetAny));
		assertTrue("all routes with matching member-of should be added to set with mbrs-by-ref: ANY",
				doc.routeSets.get("rs-set").resolve(doc).size() == 2);
		
		
		
		//Check that set w/ no mbrs-by-ref is empty
		doc = BGPRpslDocument.parseRpslDocument(new RpslObjectStringReader(
				routeMaintainedMember + routeMember + routeOrphan + routeSetEmpty));
		assertTrue("route set with no mbrs-by-ref should not load any member-of routes",
				doc.routeSets.get("rs-set").resolve(doc).size() == 0);
		
	}
	
	@Test
	public void recursiveResolveTest() {
		final String 	routeSetWithMembers				= "route-set: rs-root\nmembers: 1.1.1.0/24, rs-recur\n\n",
					 	routeSetRecurDifferentMember 	= "route-set: rs-recur\nmembers: 1.1.2.0/24\n\n",
					 	routeSetRecurSameMember			= "route-set: rs-recur\nmembers: 1.1.1.0/24\n\n",
					 	routeSetRecurCyclicMember		= "route-set: rs-recur\nmembers: 1.1.2.0/24, rs-root\n\n";
		
		//Test that set resolves child sets
		BGPRpslDocument doc = BGPRpslDocument.parseRpslDocument(new RpslObjectStringReader(routeSetWithMembers + routeSetRecurDifferentMember));
		Set<BGPRoute> resolvedRoutes = doc.getRouteSet("rs-root").resolve(doc);
		
		assertEquals("Route set with recursive member should contain route from each", 2, resolvedRoutes.size());
		assertTrue("Root route set route is resolved", resolvedRoutes.contains(new BGPRoute(AddressPrefixRange.parse("1.1.1.0/24"), null)));
		assertTrue("Root route set includes recursive members route", resolvedRoutes.contains(new BGPRoute(AddressPrefixRange.parse("1.1.2.0/24"),null)));
		
		//Test that unique route is only added once even if two sets
		doc = BGPRpslDocument.parseRpslDocument(new RpslObjectStringReader(routeSetWithMembers+ routeSetRecurSameMember));
		resolvedRoutes = doc.getRouteSet("rs-root").resolve(doc);
		
		assertEquals("Unique route should only be added to set once even if included in parent and child set", 1, resolvedRoutes.size());
		assertTrue(resolvedRoutes.contains(new BGPRoute(AddressPrefixRange.parse("1.1.1.0/24"), null)));
		
		//Test that recursive and cyclic sets resolve correctly
		doc = BGPRpslDocument.parseRpslDocument(new RpslObjectStringReader(routeSetWithMembers + routeSetRecurCyclicMember));
		resolvedRoutes = doc.getRouteSet("rs-root").resolve(doc);
		
		assertTrue("Route sets with cyclic dependencies should contain all unique member routes", 
				resolvedRoutes.size() == 2 && 
				resolvedRoutes.contains(new BGPRoute(AddressPrefixRange.parse("1.1.1.0/24"), null)) &&
				resolvedRoutes.contains(new BGPRoute(AddressPrefixRange.parse("1.1.2.0/24"),null)));
		assertEquals("Equivilent cycles of route sets contain the same members", doc.getRouteSet("rs-root").resolve(doc), doc.getRouteSet("rs-recur").resolve(doc));
	}
	
	@Test
	public void explicitMembersTest() {
		String routeSetWithMember				= "route-set: rs-mem\nmembers: 1.1.1.0/24\n\n";
		String routeSetWithMemberAndRefMember	= "route-set: rs-mem\nmembers: 1.1.1.0/24\nmbrs-by-ref:MNTR-FOO\n\n";
		String routeMemberByRef					= "route: 1.1.2.0/24\norigin: AS5\nmnt-by: MNTR-FOO\nmember-of: rs-mem\n\n";
		
		BGPRpslDocument doc = BGPRpslDocument.parseRpslDocument(new RpslObjectStringReader(routeSetWithMember + routeMemberByRef));
		//doc contains one route set which doesn't permit mbrsByRef. There should only be the explicitly declared route 1.1.1.1
		
		Set<BGPRoute> flattenedRoutes = doc.getRouteSet("rs-mem").resolve(doc);
		
		assertEquals(1, flattenedRoutes.size());
		assertEquals("1.1.1.0/24 via null", flattenedRoutes.iterator().next().toString());
		
		//test with mbrs-by-ref 
		doc = BGPRpslDocument.parseRpslDocument(new RpslObjectStringReader(routeSetWithMemberAndRefMember + routeMemberByRef));
		flattenedRoutes = doc.getRouteSet("rs-mem").resolve(doc);
		
		//expect two routes this time
		boolean memRouteFound = false;
		boolean refRouteFound = false;
		assertEquals(2, flattenedRoutes.size());
		for(BGPRoute r : flattenedRoutes) {
			if(r.toString().equals("1.1.1.0/24 via null"))
				memRouteFound=true;
			if(r.toString().equals("1.1.2.0/24 via null"))
				refRouteFound=true;
		}
		//ensure we found those and only those
		assertTrue(memRouteFound);
		assertTrue(refRouteFound);
		assertTrue(flattenedRoutes.size()==2);
	}

}
