package com.project.lumina.client.game.entity

class Item(runtimeEntityId: Long, uniqueEntityId: Long) :
    Entity(runtimeEntityId, uniqueEntityId) {

    override fun toString(): String {
        return "EntityItem(entityId=$runtimeEntityId, uniqueId=$uniqueEntityId, posX=$posX, posY=$posY, posZ=$posZ)"
    }
}