package org.whispersystems.signalservice.api.groupsv2;

import com.google.protobuf.ByteString;

import org.signal.storageservice.protos.groups.local.DecryptedApproveMember;
import org.signal.storageservice.protos.groups.local.DecryptedBannedMember;
import org.signal.storageservice.protos.groups.local.DecryptedGroup;
import org.signal.storageservice.protos.groups.local.DecryptedGroupChange;
import org.signal.storageservice.protos.groups.local.DecryptedMember;
import org.signal.storageservice.protos.groups.local.DecryptedModifyMemberRole;
import org.signal.storageservice.protos.groups.local.DecryptedPendingMember;
import org.signal.storageservice.protos.groups.local.DecryptedPendingMemberRemoval;
import org.signal.storageservice.protos.groups.local.DecryptedRequestingMember;
import org.signal.storageservice.protos.groups.local.DecryptedString;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class GroupChangeReconstruct {

  /**
   * Given a {@param fromState} and a {@param toState} creates a {@link DecryptedGroupChange} that would take the {@param fromState} to the {@param toState}.
   */
  public static DecryptedGroupChange reconstructGroupChange(DecryptedGroup fromState, DecryptedGroup toState) {
    DecryptedGroupChange.Builder builder = DecryptedGroupChange.newBuilder()
                                                               .setRevision(toState.getRevision());

    if (!fromState.getTitle().equals(toState.getTitle())) {
      builder.setNewTitle(DecryptedString.newBuilder().setValue(toState.getTitle()));
    }

    if (!fromState.getDescription().equals(toState.getDescription())) {
      builder.setNewDescription(DecryptedString.newBuilder().setValue(toState.getDescription()));
    }

    if (!fromState.getIsAnnouncementGroup().equals(toState.getIsAnnouncementGroup())) {
      builder.setNewIsAnnouncementGroup(toState.getIsAnnouncementGroup());
    }

    if (!fromState.getAvatar().equals(toState.getAvatar())) {
      builder.setNewAvatar(DecryptedString.newBuilder().setValue(toState.getAvatar()));
    }

    if (!fromState.getDisappearingMessagesTimer().equals(toState.getDisappearingMessagesTimer())) {
      builder.setNewTimer(toState.getDisappearingMessagesTimer());
    }

    if (!fromState.getAccessControl().getAttributes().equals(toState.getAccessControl().getAttributes())) {
      builder.setNewAttributeAccess(toState.getAccessControl().getAttributes());
    }

    if (!fromState.getAccessControl().getMembers().equals(toState.getAccessControl().getMembers())) {
      builder.setNewMemberAccess(toState.getAccessControl().getMembers());
    }

    Set<ByteString> fromStateMemberUuids = membersToSetOfUuids(fromState.getMembersList());
    Set<ByteString> toStateMemberUuids   = membersToSetOfUuids(toState.getMembersList());

    Set<ByteString> pendingMembersListA = pendingMembersToSetOfServiceIds(fromState.getPendingMembersList());
    Set<ByteString> pendingMembersListB = pendingMembersToSetOfServiceIds(toState.getPendingMembersList());
    
    Set<ByteString> requestingMembersListA = requestingMembersToSetOfUuids(fromState.getRequestingMembersList());
    Set<ByteString> requestingMembersListB = requestingMembersToSetOfUuids(toState.getRequestingMembersList());

    Set<ByteString> bannedMembersListA = bannedMembersToSetOfServiceIds(fromState.getBannedMembersList());
    Set<ByteString> bannedMembersListB = bannedMembersToSetOfServiceIds(toState.getBannedMembersList());

    Set<ByteString> removedPendingMemberServiceIds = subtract(pendingMembersListA, pendingMembersListB);
    Set<ByteString> removedRequestingMemberUuids   = subtract(requestingMembersListA, requestingMembersListB);
    Set<ByteString> newPendingMemberServiceIds     = subtract(pendingMembersListB, pendingMembersListA);
    Set<ByteString> newRequestingMemberUuids       = subtract(requestingMembersListB, requestingMembersListA);
    Set<ByteString> removedMemberUuids             = subtract(fromStateMemberUuids, toStateMemberUuids);
    Set<ByteString> newMemberUuids                 = subtract(toStateMemberUuids, fromStateMemberUuids);
    Set<ByteString> removedBannedMemberServiceIds  = subtract(bannedMembersListA, bannedMembersListB);
    Set<ByteString> newBannedMemberServiceIds      = subtract(bannedMembersListB, bannedMembersListA);

    Set<ByteString>                addedByInvitationUuids        = intersect(newMemberUuids, removedPendingMemberServiceIds);
    Set<ByteString>                addedByRequestApprovalUuids   = intersect(newMemberUuids, removedRequestingMemberUuids);
    Set<DecryptedMember>           addedMembersByInvitation      = intersectByUUID(toState.getMembersList(), addedByInvitationUuids);
    Set<DecryptedMember>           addedMembersByRequestApproval = intersectByUUID(toState.getMembersList(), addedByRequestApprovalUuids);
    Set<DecryptedMember>           addedMembers                  = intersectByUUID(toState.getMembersList(), subtract(newMemberUuids, addedByInvitationUuids, addedByRequestApprovalUuids));
    Set<DecryptedPendingMember>    uninvitedMembers              = intersectPendingByServiceId(fromState.getPendingMembersList(), subtract(removedPendingMemberServiceIds, addedByInvitationUuids));
    Set<DecryptedRequestingMember> rejectedRequestMembers        = intersectRequestingByUUID(fromState.getRequestingMembersList(), subtract(removedRequestingMemberUuids, addedByRequestApprovalUuids));

    for (DecryptedMember member : intersectByUUID(fromState.getMembersList(), removedMemberUuids)) {
      builder.addDeleteMembers(member.getUuid());
    }

    for (DecryptedMember member : addedMembers) {
      builder.addNewMembers(member);
    }

    for (DecryptedMember member : addedMembersByInvitation) {
      builder.addPromotePendingMembers(member);
    }

    for (DecryptedPendingMember uninvitedMember : uninvitedMembers) {
      builder.addDeletePendingMembers(DecryptedPendingMemberRemoval.newBuilder()
                                                                   .setServiceIdBinary(uninvitedMember.getServiceIdBinary())
                                                                   .setUuidCipherText(uninvitedMember.getUuidCipherText()));
    }

    for (DecryptedPendingMember invitedMember : intersectPendingByServiceId(toState.getPendingMembersList(), newPendingMemberServiceIds)) {
      builder.addNewPendingMembers(invitedMember);
    }

    Set<ByteString>                        consistentMemberUuids     = intersect(fromStateMemberUuids, toStateMemberUuids);
    Set<DecryptedMember>                   changedMembers            = intersectByUUID(subtract(toState.getMembersList(), fromState.getMembersList()), consistentMemberUuids);
    Map<ByteString, DecryptedMember>       membersUuidMap            = uuidMap(fromState.getMembersList());
    Map<ByteString, DecryptedBannedMember> bannedMembersServiceIdMap = bannedServiceIdMap(toState.getBannedMembersList());

    for (DecryptedMember newState : changedMembers) {
      DecryptedMember oldState = membersUuidMap.get(newState.getUuid());
      if (oldState.getRole() != newState.getRole()) {
        builder.addModifyMemberRoles(DecryptedModifyMemberRole.newBuilder()
                                                              .setUuid(newState.getUuid())
                                                              .setRole(newState.getRole()));
      }

      if (!oldState.getProfileKey().equals(newState.getProfileKey())) {
        builder.addModifiedProfileKeys(newState);
      }
    }

    if (!fromState.getAccessControl().getAddFromInviteLink().equals(toState.getAccessControl().getAddFromInviteLink())) {
      builder.setNewInviteLinkAccess(toState.getAccessControl().getAddFromInviteLink());
    }

    for (DecryptedRequestingMember requestingMember : intersectRequestingByUUID(toState.getRequestingMembersList(), newRequestingMemberUuids)) {
      builder.addNewRequestingMembers(requestingMember);
    }
    
    for (DecryptedRequestingMember requestingMember : rejectedRequestMembers) {
      builder.addDeleteRequestingMembers(requestingMember.getUuid());
    }

    for (DecryptedMember member : addedMembersByRequestApproval) {
      builder.addPromoteRequestingMembers(DecryptedApproveMember.newBuilder()
                                                                .setUuid(member.getUuid())
                                                                .setRole(member.getRole()));
    }

    if (!fromState.getInviteLinkPassword().equals(toState.getInviteLinkPassword())) {
      builder.setNewInviteLinkPassword(toState.getInviteLinkPassword());
    }

    for (ByteString serviceIdBinary : removedBannedMemberServiceIds) {
      builder.addDeleteBannedMembers(DecryptedBannedMember.newBuilder().setServiceIdBinary(serviceIdBinary).build());
    }

    for (ByteString serviceIdBinary : newBannedMemberServiceIds) {
      DecryptedBannedMember.Builder newBannedBuilder = DecryptedBannedMember.newBuilder().setServiceIdBinary(serviceIdBinary);
      DecryptedBannedMember         bannedMember     = bannedMembersServiceIdMap.get(serviceIdBinary);
      if (bannedMember != null) {
        newBannedBuilder.setTimestamp(bannedMember.getTimestamp());
      }

      builder.addNewBannedMembers(newBannedBuilder);
    }

    return builder.build();
  }

  private static Map<ByteString, DecryptedMember> uuidMap(List<DecryptedMember> membersList) {
    Map<ByteString, DecryptedMember> map = new LinkedHashMap<>(membersList.size());
    for (DecryptedMember member : membersList) {
      map.put(member.getUuid(), member);
    }
    return map;
  }

  private static Map<ByteString, DecryptedBannedMember> bannedServiceIdMap(List<DecryptedBannedMember> membersList) {
    Map<ByteString, DecryptedBannedMember> map = new LinkedHashMap<>(membersList.size());
    for (DecryptedBannedMember member : membersList) {
      map.put(member.getServiceIdBinary(), member);
    }
    return map;
  }

  private static Set<DecryptedMember> intersectByUUID(Collection<DecryptedMember> members, Set<ByteString> uuids) {
    Set<DecryptedMember> result = new LinkedHashSet<>(members.size());
    for (DecryptedMember member : members) {
      if (uuids.contains(member.getUuid()))
        result.add(member);
    }
    return result;
  }

  private static Set<DecryptedPendingMember> intersectPendingByServiceId(Collection<DecryptedPendingMember> members, Set<ByteString> serviceIds) {
    Set<DecryptedPendingMember> result = new LinkedHashSet<>(members.size());
    for (DecryptedPendingMember member : members) {
      if (serviceIds.contains(member.getServiceIdBinary()))
        result.add(member);
    }
    return result;
  }
  
  private static Set<DecryptedRequestingMember> intersectRequestingByUUID(Collection<DecryptedRequestingMember> members, Set<ByteString> uuids) {
    Set<DecryptedRequestingMember> result = new LinkedHashSet<>(members.size());
    for (DecryptedRequestingMember member : members) {
      if (uuids.contains(member.getUuid()))
        result.add(member);
    }
    return result;
  }

  private static Set<ByteString> pendingMembersToSetOfServiceIds(Collection<DecryptedPendingMember> pendingMembers) {
    Set<ByteString> serviceIds = new LinkedHashSet<>(pendingMembers.size());
    for (DecryptedPendingMember pendingMember : pendingMembers) {
      serviceIds.add(pendingMember.getServiceIdBinary());
    }
    return serviceIds;
  }

  private static Set<ByteString> requestingMembersToSetOfUuids(Collection<DecryptedRequestingMember> requestingMembers) {
    Set<ByteString> uuids = new LinkedHashSet<>(requestingMembers.size());
    for (DecryptedRequestingMember requestingMember : requestingMembers) {
      uuids.add(requestingMember.getUuid());
    }
    return uuids;
  }

  private static Set<ByteString> membersToSetOfUuids(Collection<DecryptedMember> members) {
    Set<ByteString> uuids = new LinkedHashSet<>(members.size());
    for (DecryptedMember member : members) {
      uuids.add(member.getUuid());
    }
    return uuids;
  }

  private static Set<ByteString> bannedMembersToSetOfServiceIds(Collection<DecryptedBannedMember> bannedMembers) {
    Set<ByteString> serviceIds = new LinkedHashSet<>(bannedMembers.size());
    for (DecryptedBannedMember bannedMember : bannedMembers) {
      serviceIds.add(bannedMember.getServiceIdBinary());
    }
    return serviceIds;
  }

  private static <T> Set<T> subtract(Collection<T> a, Collection<T> b) {
    Set<T> result = new LinkedHashSet<>(a);
    result.removeAll(b);
    return result;
  }

  private static <T> Set<T> subtract(Collection<T> a, Collection<T> b, Collection<T> c) {
    Set<T> result = new LinkedHashSet<>(a);
    result.removeAll(b);
    result.removeAll(c);
    return result;
  }

  private static <T> Set<T> intersect(Collection<T> a, Collection<T> b) {
    Set<T> result = new LinkedHashSet<>(a);
    result.retainAll(b);
    return result;
  }
}
